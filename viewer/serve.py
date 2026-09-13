"""
Serves the fight replay viewer, viewer/replay.html, with a live list of every run's replays, and the league standings
page beside it, viewer/league.html at /league.

    python viewer/serve.py                  start the viewer, or find the one already running, and open the browser
    python viewer/serve.py --run imitate    ... on the newest replay of that run
    python viewer/serve.py --league         ... on the league standings instead of a replay
    python viewer/serve.py --port 8800      try that port first
    python viewer/serve.py --no-browser     only print the address

    python viewer/serve.py --minecraft-jar PATH   take mob and block textures from that Minecraft client jar
    python viewer/serve.py --export [FILE] [--run NAME | --path FILE_OR_FOLDER] [--count 20]
        bakes the newest replays into a standalone copy of the page, runs/<name>/replays.html unless FILE is given;
        the copy carries three.js inline, so its 3D view works without the vendor folder

The 3D view draws real Minecraft mobs and blocks when it can read their textures from the user's own Minecraft client
jar, which the mod's build already downloaded into the Gradle cache (Fabric Loom's or NeoForge's). Mojang's licence
does not allow handing those textures on, so they are read from the jar while serving and kept only in memory: never
written to disk, never put into an export. The agent's skin is the mod's own. Without a jar the 3D view draws boxes
for the mobs and the blocks in their map colours.

Each mob's shape is a different thing from its texture: viewer/models/<mob>.json holds the tree of cuboids its model is
made of, written out of the game by `gradlew :fabric:exportMobModels` and committed, since it is generated data derived
from the game rather than an asset of Mojang's. Those files are served straight off disk at /models/.

The page can also delete replays, through POST /api/delete. Like everything else here it answers only to the page on
this machine, and it only ever deletes finished replays, *.json files straight inside runs/<name>/replays/.

The league page reads what a league run already writes and nothing else: the trainer's tables in runs/<name>/league/,
the run's eval.csv, and the workers' per-fight records in runs/<name>/league/results/. Nothing there is ever written.
The per-fight records run to millions of lines, so they are added up once and then read on from where they got to, and
only the sums are served; see Fights.

Those same records are what /api/matchups/<run> serves the replay list: which opponent, loadout and ground each replay
on disk was, which the replay files themselves cannot say cheaply. A replay names its run, iteration and outcome in its
first few hundred bytes, but the opponent sits in "entities" behind fifty to a hundred kilobytes of blocks, so reading
it from the file would mean reading the file. The records have it a line a fight, and the run has already written them.

Standard library only, so any Python 3.7 or newer runs it. Everything is found from this file's own location: the
repository is the folder above viewer/, and replays are runs/<name>/replays/*.json. A checkout with no runs of its own
reads the main checkout's instead, since development happens in a worktree and a worktree has none; see find_runs.
The server listens on 127.0.0.1 only, and leaves runs/.replay-viewer.json behind while it runs so a second start opens
the browser on it instead. The 3D view's three.js is viewer/vendor/three.cjs, the official build of the version named
in viewer/README.md.
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import signal
import socket
import socketserver
import sys
import threading
import time
import urllib.parse
import urllib.request
import webbrowser
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

APP = 'mmai-replay-viewer'
VERSION = 8     # what this server can serve; an older one still running is not reused (1: no 3D, 2: no textures,
                # 3: no block textures and no deleting, 4: no league page, 5: no matchups for the replay list,
                # 6: no pair table, so the league page's matrix cannot say where the fights are being sent,
                # 7: no mob shapes, so every mob in 3D is a humanoid box in its own skin)
HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
PAGE = HERE / 'replay.html'
LEAGUE_PAGE = HERE / 'league.html'
VENDOR = HERE / 'vendor'
MOB_MODELS = HERE / 'models'
AGENT_SKIN = ROOT / 'mod' / 'common' / 'src' / 'main' / 'resources' / 'assets' / 'modular_mob_ai' / 'textures' / 'entity' / 'agent.png'
MINECRAFT = '1.21.1'    # the version the mod is built against, whose jar is preferred
TEXTURE_PATH = re.compile(r'^textures/[a-z0-9_/.-]+\.png$')
DEFAULT_PORT = 8765
PLACEHOLDER = '__MMAI_REPLAYS__'
THREE_PLACEHOLDER = '__MMAI_THREE__'
VENDOR_TYPES = {'.cjs': 'text/javascript; charset=utf-8', '.js': 'text/javascript; charset=utf-8'}
FORMAT = 2      # the replay format version the page draws; older replays have no blocks, and are listed only to be deleted

# The few top-level fields the list shows. The recorder writes them before the blocks, so the first few kilobytes of
# a file hold them and the rest never needs reading just to list it.
_FIELD = re.compile(r'"(version|outcome|ticks|iteration|biome|worker|fight|brain|run)"\s*:\s*("(?:[^"\\]|\\.)*"|-?\d+(?:\.\d+)?|null)')
_headers = {}

# A replay's name, w<worker>-f<fight>.json, which is where the worker and the fight number come from when a file is too
# old to carry them in its fields, and what the recording stride is measured from.
_REPLAY_NAME = re.compile(r'^w(\d+)-f(\d+)\.json$', re.I)

# The last answer /api/runs gave, against the names, sizes and times the scan it was built from saw. Listing the folders
# is cheap and reading a header is cached, so what this saves is everything after: over twenty four thousand replays,
# building the entries into 5 MB of JSON and hashing it measured 70 ms of the 220 an answer took, and five megabytes of
# garbage, every three seconds for as long as a page is open.
_listing = {'key': None, 'body': b''}


def same_path(a, b):
    return os.path.normcase(os.path.realpath(str(a))) == os.path.normcase(os.path.realpath(str(b)))


def main_checkout(root):
    """
    The checkout a git worktree was made from, or None for one that is not a worktree. A worktree's .git is a file
    holding "gitdir: <main>/.git/worktrees/<name>", so the checkout is the folder above the .git in that path. Read out
    of the file rather than asked of git, since this server needs nothing but Python on the machine.
    """
    marker = root / '.git'
    try:
        if not marker.is_file():
            return None
        line = marker.read_text(encoding='utf-8').strip()
    except OSError:
        return None
    if not line.startswith('gitdir:'):
        return None
    gitdir = Path(line[len('gitdir:'):].strip())
    for folder in [gitdir] + list(gitdir.parents):
        if folder.name == '.git':
            return folder.parent
    return None


def find_runs():
    """
    The runs folder to serve, and whether it is this checkout's own. The repository's rule is that development happens
    in a git worktree, since a Gradle build in the checkout a run trains from breaks that run — and a worktree has no
    runs of its own, so a viewer started there would list nothing at all and say the machine had never trained. It
    reads the main checkout's instead, found the way scripts\\_common.ps1 finds the trainer's environment: through
    git's own files. Never through a junction, which is what cost this machine its PyTorch once.

    A borrowed folder is only read from. Nothing is written into another checkout's runs, not even the lock file that
    says where this viewer is listening, so a viewer serving the main checkout keeps the port a plain start finds.
    """
    own = ROOT / 'runs'
    if own.is_dir():
        return own, True
    main = main_checkout(ROOT)
    borrowed = (main / 'runs') if main else None
    return (borrowed, False) if borrowed and borrowed.is_dir() else (own, True)


RUNS, OWN_RUNS = find_runs()
LOCK = (RUNS / '.replay-viewer.json') if OWN_RUNS else None


def is_replay_name(name):
    """A finished replay file: the recorder writes under a temporary name first and renames at the end."""
    lower = name.lower()
    return lower.endswith('.json') and not name.startswith('.') and '.tmp' not in lower


def replay_stride(names):
    """
    The step between the fight numbers of one worker's replays, which is the -ReplayEvery the run was given: the recorder
    writes fight 0, N, 2N, … of each worker, so the gaps between the names it left are that N. Measured rather than
    assumed, because nothing a run writes down says what it was started with; a run resumed with a different number
    shows the smallest of them, and too few names to tell show nothing at all.
    """
    per = {}
    for name in names:
        match = _REPLAY_NAME.match(name)
        if match:
            per.setdefault(match.group(1), set()).add(int(match.group(2)))
    gaps = []
    for numbers in per.values():
        ordered = sorted(numbers)
        gaps += [b - a for a, b in zip(ordered, ordered[1:])]
    return min(gaps) if gaps else None


def read_header(path, stat):
    key = (stat.st_mtime_ns, stat.st_size)
    cached = _headers.get(path)
    if cached and cached[0] == key:
        return cached[1]
    fields = {}
    try:
        with open(path, 'rb') as f:
            head = f.read(4096).decode('utf-8', 'replace')
        for match in _FIELD.finditer(head):
            if match.group(1) not in fields:
                fields[match.group(1)] = json.loads(match.group(2))
    except (OSError, ValueError):
        pass
    _headers[path] = (key, fields)
    return fields


def scan_replays():
    """
    Every runs/<name>/replays folder with its replays, newest first, and a key for the whole scan beside it: what the
    scan saw of every file, so an answer already built for the same files can be handed out again. The page polls every
    three seconds and twenty four thousand replays are five megabytes of JSON, none of which changes between two polls
    that found the same files.
    """
    runs, seen, key = [], set(), hashlib.sha1()
    if RUNS.is_dir():
        for run_dir in sorted(RUNS.iterdir(), key=lambda d: d.name):
            folder = run_dir / 'replays'
            if not folder.is_dir():
                continue
            replays = []
            try:
                entries = list(os.scandir(folder))
            except OSError:
                continue
            for entry in entries:
                try:
                    if not entry.is_file() or not is_replay_name(entry.name):
                        continue
                    stat = entry.stat()
                except OSError:
                    continue    # renamed or deleted while we looked
                seen.add(entry.path)
                key.update(('%s|%d|%d\n' % (entry.path, stat.st_mtime_ns, stat.st_size)).encode('utf-8', 'replace'))
                replay = {'file': entry.name, 'size': stat.st_size, 'mtime': round(stat.st_mtime, 3)}
                replay.update(read_header(entry.path, stat))
                if not isinstance(replay.get('version'), int) or replay['version'] < FORMAT:
                    replay['old'] = True
                replays.append(replay)
            replays.sort(key=lambda r: r['mtime'], reverse=True)
            newest = replays[0]['mtime'] if replays else folder.stat().st_mtime
            runs.append({'name': run_dir.name, 'mtime': newest, 'replays': replays})
    for path in [p for p in _headers if p not in seen]:
        del _headers[path]
    runs.sort(key=lambda r: r['mtime'], reverse=True)
    return runs, key.hexdigest()


def list_runs():
    """Every runs/<name>/replays folder with its replays, newest first."""
    return scan_replays()[0]


def listing_body():
    """
    The bytes /api/runs answers with, built again only when a replay appeared, went, or was written over. The scan
    itself happens every time, since that is how a file that changed is noticed at all; it is only the megabytes after
    it that are kept.
    """
    runs, key = scan_replays()
    if key != _listing['key']:
        _listing['body'] = json.dumps({'runs': runs}, separators=(',', ':')).encode('utf-8')
        _listing['key'] = key
    return _listing['body']


def plain_name(part):
    """A single file or folder name, with nothing in it that could lead anywhere else."""
    return isinstance(part, str) and part not in ('', '.', '..') and not any(c in part for c in '/\\:\0')


def replay_folder(run):
    """
    runs/<run>/replays for a plain run name, or None. The folder has to be exactly there once every link is followed:
    one that is a link or junction to somewhere else is refused, so nothing outside runs/ is ever read or deleted.
    """
    if not plain_name(run):
        return None
    folder = RUNS / run / 'replays'
    expected = os.path.join(os.path.realpath(str(RUNS)), run, 'replays')
    if not folder.is_dir() or os.path.normcase(os.path.realpath(str(folder))) != os.path.normcase(expected):
        return None
    return folder


def replay_file(folder, file):
    """A finished replay straight inside a folder replay_folder gave: a plain file, never a link to one elsewhere."""
    if not folder or not plain_name(file) or not is_replay_name(file):
        return None
    path = folder / file
    return path if path.is_file() and not path.is_symlink() else None


def replay_path(run, file):
    """The replay runs/<run>/replays/<file>, or None for anything that is not a finished replay file right there."""
    return replay_file(replay_folder(run), file)


def delete_replays(request):
    """
    Deletes what the page asked for: {"replays": [{"run", "file"}...], "runs": [...], "all": true}, any of them. Every
    file has to be a finished replay straight inside a runs/<name>/replays/ folder replay_folder accepts, or nothing is
    deleted for it. Each folder is checked once, which keeps deleting a run of twenty thousand replays quick.
    """
    folders, targets, refused = {}, {}, []

    def folder_of(run):
        if not plain_name(run):
            return None
        if run not in folders:
            folders[run] = replay_folder(run)
        return folders[run]

    listed = lambda key: request.get(key) if isinstance(request.get(key), list) else []
    for entry in listed('replays'):
        entry = entry if isinstance(entry, dict) else {}
        path = replay_file(folder_of(entry.get('run')), entry.get('file'))
        if path:
            targets[str(path)] = path
        else:
            refused.append('%s/%s' % (entry.get('run'), entry.get('file')))
    runs = [r for r in listed('runs') if isinstance(r, str)]
    if request.get('all') is True and RUNS.is_dir():
        runs += [d.name for d in RUNS.iterdir() if d.is_dir()]
    for run in runs:
        folder = folder_of(run)
        try:
            names = [e.name for e in os.scandir(folder)] if folder else []
        except OSError:
            names = []
        targets.update((str(p), p) for p in (replay_file(folder, n) for n in names) if p)
    deleted = 0
    for path in targets.values():
        try:
            path.unlink()
            deleted += 1
        except OSError:
            refused.append(path.name)
    return {'deleted': deleted, 'failed': refused}


# ---- the league ------------------------------------------------------------------------------------------------------
#
# Everything the league page shows comes from files a league run already writes. The trainer's own tables are small and
# read whole every time; the workers' per-fight records are not, and are added up once and then only read on from where
# they got to. Nothing here writes anything.

# The trainer's tables, as trainer/mmai/league.py writes them, and what the run's own evaluation is in. Its
# evaluations.csv is left out on purpose: the same thing, a checkpoint's record against each opponent, comes out of the
# per-fight records with the loadouts and the deaths beside it, and it is one of the two tables that grows without end.
#
# pairs.csv is the matchmaking one row deeper: what the workers draw is a pairing, one loadout against one opponent, so
# it holds each pairing's share of the training fights and the chance the trainer estimates the agent has in it. It is
# newer than the runs training today and a run that has none simply has no such table; read_table gives an empty one and
# the page draws what it always did. It is loadouts times opponents, 490 rows at the start of a league run and about
# 1,530 once every rung of the difficulty ladder is open, so it is read whole with the rest of them.
LEAGUE_TABLES = ('ratings', 'opponents', 'loadouts', 'ground', 'matchmaking', 'pairs')
EVAL_TABLE = 'eval.csv'

# Where each field of a per-fight record is, as the game's gametest/league/League#write puts them. The columns grow to
# the right and never move, so a line from before one was added simply stops early.
FIGHT_FIELDS = ('iteration', 'kind', 'opponent', 'loadout', 'opponent_loadout', 'outcome', 'ticks', 'cause', 'site',
                'finish', 'weapon', 'swaps', 'uses', 'shots', 'replay')
WON = {'win': 'wins', 'loss': 'losses', 'timeout': 'timeouts', 'draw': 'draws'}

# Recorded fights kept per run, newest last: what a row is linked to a fight of that very matchup by, and what the
# replay list reads a replay's opponent and loadout from. High enough to hold every replay a run has on disk, because
# one it drops is a replay the page can then say nothing about: a run recording one fight in 200 writes about 8,000 in
# two million fights, and the longest run on this machine had 7,875 on disk against a limit of 4,000 — half of them
# silently unlabelled. These are a name and six short strings each, a couple of megabytes at this size.
RECORDED_LIMIT = 20000

# How many bytes of per-fight records are read in one go, so that a first read of a quarter of a million fights does not
# hold the whole file in memory twice over.
FIGHT_CHUNK = 4 << 20


def league_folder(run):
    """
    runs/<run>/league for a plain run name, or None. The same rule as replay_folder: the folder has to be exactly there
    once every link is followed, so nothing outside runs/ is ever read.
    """
    if not plain_name(run):
        return None
    folder = RUNS / run / 'league'
    expected = os.path.join(os.path.realpath(str(RUNS)), run, 'league')
    if not folder.is_dir() or os.path.normcase(os.path.realpath(str(folder))) != os.path.normcase(expected):
        return None
    return folder


def read_table(path):
    """A comma separated table with a header, as a list of dicts. Empty for one that is not there, or is being replaced."""
    try:
        lines = path.read_text(encoding='utf-8').splitlines()
    except OSError:
        return []
    if not lines:
        return []
    header = [name.strip() for name in lines[0].split(',')]
    rows = []
    for line in lines[1:]:
        if not line.strip():
            continue
        parts = [part.strip() for part in line.split(',')]
        rows.append({name: parts[index] if index < len(parts) else '' for index, name in enumerate(header)})
    return rows


def bucket():
    """How a set of fights went, and what the agent did with its hands over them."""
    return {'fights': 0, 'wins': 0, 'losses': 0, 'timeouts': 0, 'draws': 0, 'ticks': 0,
            'swaps': 0, 'uses': 0, 'shots': 0, 'shot_fights': 0, 'weapons': {}, 'causes': {}}


def count(into, fight):
    into['fights'] += 1
    into[WON[fight['outcome']]] += 1
    into['ticks'] += fight['ticks']
    into['swaps'] += fight['swaps']
    into['uses'] += fight['uses']
    into['shots'] += fight['shots']
    into['shot_fights'] += 1 if fight['shots'] > 0 else 0
    if fight['weapon'] and fight['weapon'] != '-':
        into['weapons'][fight['weapon']] = into['weapons'].get(fight['weapon'], 0) + 1
    # Only a fight the agent died in says what of; anything else records nothing there.
    if fight['cause'] and fight['cause'] != '-':
        into['causes'][fight['cause']] = into['causes'].get(fight['cause'], 0) + 1


def won(pair, outcome):
    """A [fights, wins] pair, which is all a cell of the opponent by loadout table needs."""
    pair[0] += 1
    pair[1] += 1 if outcome == 'win' else 0
    return pair


def most(counts):
    """The commonest of something and how many of the fights it was, as [name, count], or None for nothing counted."""
    if not counts:
        return None
    name = max(counts, key=lambda key: (counts[key], key))
    return [name, counts[name]]


class Fights:
    """
    What one run's per-fight records add up to: runs/<run>/league/results/w*.csv, a line a fight, written by the workers.

    One of these per run for as long as the server runs. The files are only ever appended to, so a refresh reads the new
    bytes and nothing more: a run of a quarter of a million fights costs one pass and then nothing, which is what lets the
    page poll while training goes on. A file that has grown shorter than it was is a different run under the same name, and
    everything is read again from the start.

    A **model** here is an evaluated checkpoint: the iteration named by the fights of kind ``eval``, which are the ones
    played on a checkpoint's most likely action and the ones the trainer rates. Training fights carry whichever iteration
    the workers happened to be on, a policy that samples and is gone the next iteration, so they are counted for the run as
    a whole and not per model.
    """

    def __init__(self, folder):
        self.folder = folder
        self.forget()

    def forget(self):
        self.offsets = {}
        self.overall = {'train': bucket(), 'eval': bucket()}
        self.opponents = {}     # opponent -> [fights, wins], over every fight of the run
        self.loadouts = {}      # loadout  -> [fights, wins]
        self.sites = {}         # site     -> [fights, wins]: the kind of ground it was fought on
        self.cells = {}         # 'opponent\tloadout' -> [fights, wins]: the win rate by loadout and opponent
        self.models = {}        # iteration -> one evaluated checkpoint's own numbers
        # Replay file name -> what that fight was, in the order the workers wrote them. A dict rather than a list
        # because both readers want it by name: the league page to link a row to the fights of its own matchup, the
        # replay list to say which opponent and loadout a file on disk holds.
        self.recorded = {}
        self.named = 0          # fights that named a replay, including any dropped at RECORDED_LIMIT

    def refresh(self):
        """Reads whatever the workers have appended since the last look."""
        results = self.folder / 'results'
        try:
            files = sorted(p for p in results.glob('w*.csv') if p.is_file())
        except OSError:
            return self
        # A file shorter than it was is a different run under the same name, and everything is read again from the start.
        # One that vanished between the listing and the look is simply gone; the next refresh lists again.
        try:
            if any(self.offsets.get(path.name, 0) > path.stat().st_size for path in files):
                self.forget()
        except OSError:
            return self
        for path in files:
            start = self.offsets.get(path.name, 0)
            try:
                with open(path, 'rb') as stream:
                    stream.seek(start)
                    while True:
                        data = stream.read(FIGHT_CHUNK)
                        if not data:
                            break
                        # Only whole lines; a worker may be half way through writing the last one.
                        end = data.rfind(b'\n') + 1
                        if not end:
                            break
                        start += end
                        for line in data[:end].decode('utf-8', 'replace').splitlines():
                            self.take(line)
            except OSError:
                pass    # being written to or gone; the next refresh picks up where this got to
            self.offsets[path.name] = start
        return self

    def take(self, line):
        parts = line.strip().split(',')
        if len(parts) < 7 or parts[1] not in ('train', 'eval') or parts[5] not in WON:
            return
        fight = {name: parts[index] if index < len(parts) else '' for index, name in enumerate(FIGHT_FIELDS)}
        try:
            fight['iteration'] = int(fight['iteration'])
            fight['ticks'] = int(fight['ticks'])
            for name in ('swaps', 'uses', 'shots'):
                fight[name] = int(fight[name]) if fight[name] not in ('', '-') else 0
        except ValueError:
            return

        count(self.overall[fight['kind']], fight)
        won(self.opponents.setdefault(fight['opponent'], [0, 0]), fight['outcome'])
        won(self.loadouts.setdefault(fight['loadout'], [0, 0]), fight['outcome'])
        won(self.sites.setdefault(fight['site'] or '-', [0, 0]), fight['outcome'])
        won(self.cells.setdefault(fight['opponent'] + '\t' + fight['loadout'], [0, 0]), fight['outcome'])

        if fight['kind'] == 'eval':
            model = self.models.get(fight['iteration'])
            if model is None:
                model = self.models[fight['iteration']] = {'iteration': fight['iteration'], 'own': bucket(),
                                                           'opponents': {}, 'loadouts': {}}
            count(model['own'], fight)
            won(model['opponents'].setdefault(fight['opponent'], [0, 0]), fight['outcome'])
            won(model['loadouts'].setdefault(fight['loadout'], [0, 0]), fight['outcome'])

        # Only a plain replay name, never a path: a record is a file the game wrote, but what it says ends up in a page.
        if fight['replay'] not in ('', '-') and plain_name(fight['replay']) and is_replay_name(fight['replay']):
            self.named += 1
            self.recorded[fight['replay']] = {
                'file': fight['replay'], 'iteration': fight['iteration'], 'kind': fight['kind'],
                'opponent': fight['opponent'], 'loadout': fight['loadout'], 'outcome': fight['outcome'],
                'ticks': fight['ticks'], 'site': fight['site'], 'cause': fight['cause']}
            # A resumed run numbers its replays on from the last one in the folder, so a name never comes round twice
            # and the oldest entry is the oldest recording. Dropping it loses only the label on a file still on disk.
            while len(self.recorded) > RECORDED_LIMIT:
                self.recorded.pop(next(iter(self.recorded)))

    def on_disk(self, replays):
        """
        The recorded fights whose replay is really on disk, oldest first, since a name is written down in the records
        before the file itself is finished, and replays are deleted from the list without the records changing.
        """
        kept = set(replays)
        return [one for one in self.recorded.values() if one['file'] in kept]

    def recording(self, present):
        """
        How much of this run was recorded at all, which is what makes an empty row readable: a matchup with no replay is
        not a page that lost them but a run that recorded one fight in -ReplayEvery per worker and none of them this one.
        Takes what on_disk already worked out, since both pages want the list as well as the count.
        """
        fought = self.overall['train']['fights'] + self.overall['eval']['fights']
        return {'fights': fought, 'named': self.named, 'labelled': len(self.recorded), 'on_disk': len(present),
                'every': replay_stride(self.recorded)}

    def summary(self, model=None, replays=()):
        """
        What the page is served. Every model as one row, and the one it asked about in full; the recorded fights are
        narrowed to the replays that are really on disk, since a name was written down before its file was.

        A row carries the weapon and the death a model saw most rather than every one of them, because the whole of those
        for every checkpoint of a long run is most of a megabyte and the page shows one model's at a time.
        """
        models = []
        for one in sorted(self.models.values(), key=lambda one: one['iteration']):
            row = {name: value for name, value in one['own'].items() if name not in ('weapons', 'causes')}
            row['iteration'] = one['iteration']
            row['weapon'] = most(one['own']['weapons'])
            row['death'] = most(one['own']['causes'])
            models.append(row)
        picked = self.models.get(model)
        present = self.on_disk(replays)
        return {
            'overall': self.overall,
            'opponents': self.opponents,
            'loadouts': self.loadouts,
            'sites': self.sites,
            'cells': self.cells,
            'models': models,
            'model': picked,
            'recorded': present,
            'recording': self.recording(present),
        }


_fights = {}


def league_fights(run, folder):
    """The sums for that run, brought up to date. Kept between requests, since a refresh only reads what is new."""
    if run not in _fights:
        _fights[run] = Fights(folder)
    return _fights[run].refresh()


def league_runs():
    """Every run with a league in it, newest first: what the picker lists, and no more than it needs."""
    runs = []
    if not RUNS.is_dir():
        return runs
    for run_dir in RUNS.iterdir():
        folder = league_folder(run_dir.name)
        ratings = folder / 'ratings.csv' if folder else None
        if not ratings or not ratings.is_file():
            continue
        players = read_table(ratings)
        checkpoints = [row for row in players if row.get('kind') == 'checkpoint']
        rated = sum(int(row.get('games') or 0) for row in checkpoints)
        try:
            when = ratings.stat().st_mtime
        except OSError:
            continue
        runs.append({'name': run_dir.name, 'mtime': round(when, 3), 'players': len(players),
                     'checkpoints': len(checkpoints), 'rated': rated,
                     'models': sorted(row['player'] for row in players if row.get('kind') == 'model')})
    runs.sort(key=lambda run: run['mtime'], reverse=True)
    return runs


def league(run, model=None):
    """Everything the page draws for one run, or None when that run has no league."""
    folder = league_folder(run)
    if folder is None:
        return None
    tables = {name: read_table(folder / (name + '.csv')) for name in LEAGUE_TABLES}
    evaluations = read_table(folder.parent / EVAL_TABLE)
    best = [row for row in evaluations if row.get('best') == '1']
    names = {entry['file'] for entry in list_replays(run)}
    return {
        'run': run,
        'best': int(best[-1]['iteration']) if best and best[-1].get('iteration', '').isdigit() else None,
        'tables': tables,
        'eval': evaluations,
        'fights': league_fights(run, folder).summary(model, names),
    }


def matchups(run):
    """
    What each replay of a run was a fight of, for the replay list: the opponent, the loadout, the kind of ground and what
    killed the agent, by file name. None of that is in a replay's first few hundred bytes — the opponent sits in
    "entities" behind the site's blocks — but the run's per-fight records have it a line a fight, already added up here
    for the league page and read on from where they got to on every look.

    Only the files that are really on disk, so the list gets an entry for everything it shows and nothing more. A run
    without a league says so instead: those replays keep to what their own headers hold.
    """
    folder = league_folder(run)
    if folder is None:
        return {'run': run, 'league': False, 'fights': {}, 'recording': None}
    names = {entry['file'] for entry in list_replays(run)}
    fights = league_fights(run, folder)
    present = fights.on_disk(names)
    return {'run': run, 'league': True, 'fights': {one['file']: one for one in present},
            'recording': fights.recording(present)}


def list_replays(run):
    """The replays of one run that are actually on disk, so a row never links to a file that was never written."""
    folder = replay_folder(run)
    if folder is None:
        return []
    try:
        return [{'file': entry.name} for entry in os.scandir(folder) if entry.is_file() and is_replay_name(entry.name)]
    except OSError:
        return []


def jar_version(path):
    """The Minecraft version of a jar that holds the vanilla mob textures, or None."""
    try:
        with zipfile.ZipFile(path) as jar:
            jar.getinfo('assets/minecraft/textures/entity/illager/vindicator.png')
            try:
                return json.loads(jar.read('version.json')).get('id') or '?'
            except (KeyError, ValueError):
                return '?'
    except (OSError, KeyError, zipfile.BadZipFile):
        return None


def find_minecraft_jar():
    """
    A Minecraft client jar from the Gradle caches that Fabric Loom and NeoForge's tools fill when the mod is built: the
    version the mod targets before any other, a plain client jar before a merged or remapped one. Only those tools'
    folders are searched, not the whole cache. Returns (path, version) or (None, None).
    """
    home = Path(os.environ.get('GRADLE_USER_HOME') or Path.home() / '.gradle')
    caches = home / 'caches'
    if not caches.is_dir():
        return None, None
    candidates = []
    for folder in caches.iterdir():
        if folder.is_dir() and re.search(r'loom|neoform|moddev|forge|minecraft', folder.name, re.I):
            for jar in folder.rglob('*.jar'):
                name = jar.name.lower()
                if ('client' in name or 'merged' in name) and 'sources' not in name:
                    candidates.append(jar)

    def preference(jar):
        text = str(jar).lower()
        return (MINECRAFT not in text, 'client' not in jar.name.lower(), 'intermediary' in text or 'mappings' in text, len(text))

    for jar in sorted(candidates, key=preference):
        version = jar_version(jar)
        if version:
            return jar, version
    return None, None


class Textures:
    """Vanilla textures straight out of the user's Minecraft jar, held in memory only."""

    def __init__(self, jar, version, how):
        self.jar, self.version, self.how = jar, version, how
        self.zip = zipfile.ZipFile(jar) if jar else None
        self.cache = {}
        self.lock = threading.Lock()     # one zip file handle, shared by the server's threads

    def read(self, path):
        if not self.zip or not TEXTURE_PATH.match(path) or '..' in path:
            return None
        with self.lock:
            if path not in self.cache:
                try:
                    self.cache[path] = self.zip.read('assets/minecraft/' + path)
                except KeyError:
                    self.cache[path] = None
            return self.cache[path]

    def blocks(self):
        """The names of every block texture in the jar, so the page can tell which ones a block may have without asking."""
        if not self.zip:
            return []
        prefix = 'assets/minecraft/textures/block/'
        return sorted(n[len(prefix):-4] for n in self.zip.namelist() if n.startswith(prefix) and n.endswith('.png')
                      and '/' not in n[len(prefix):])

    def entities(self):
        """
        Every entity texture in the jar, as paths under textures/entity, for the same reason the block names are served:
        so the page asks for what is there rather than trying its luck. Vanilla files a mob's texture by family as often
        as by its own name — a cave spider under spider/, a zoglin under hoglin/, every illager under illager/ — so a page
        that guesses entity/<id>/<id> and entity/<id> misses once for every mob of the older convention and finds nothing
        at all for the rest. Those misses were the "No such texture" lines in this server's log.
        """
        if not self.zip:
            return []
        prefix = 'assets/minecraft/textures/entity/'
        return sorted(n[len(prefix):-4] for n in self.zip.namelist() if n.startswith(prefix) and n.endswith('.png'))

    def describe(self):
        if not self.zip:
            return {'minecraft': False, 'agent': AGENT_SKIN.is_file()}
        return {'minecraft': True, 'version': self.version, 'jar': self.jar.name, 'agent': AGENT_SKIN.is_file(), 'blocks': True}


TEXTURES = Textures(None, None, '')


def vendor_path(name):
    """A file straight inside viewer/vendor, or None."""
    if not name or name.startswith('.') or any(c in name for c in '/\\:\0'):
        return None
    path = VENDOR / name
    return path if path.is_file() and same_path(path.parent, VENDOR) else None


def mob_model_path(name):
    """
    One mob's shape, straight inside viewer/models, or None. The name is a mob id or 'index', so nothing but a plain
    lower-case name and .json is even looked for; these are checked-in files the page asks for by name, and the
    restriction is what keeps a path out of the request.
    """
    if not re.fullmatch(r'[a-z0-9_]+\.json', name or ''):
        return None
    path = MOB_MODELS / name
    return path if path.is_file() and same_path(path.parent, MOB_MODELS) else None


class Handler(BaseHTTPRequestHandler):
    server_version = 'MmaiReplayViewer/1'

    def local_request(self):
        # Answer only to the addresses the page is opened on, which keeps other websites from reading replays
        # through DNS rebinding.
        port = self.server.server_address[1]
        if (self.headers.get('Host') or '').lower() not in ('127.0.0.1:%d' % port, 'localhost:%d' % port):
            self.send_error(403, 'Local requests only')
            return False
        return True

    def from_the_page(self):
        """
        Whether a request that changes something comes from the page itself. Another website open in the same browser
        can send a plain form POST here, but not one with a JSON body and this header: for those the browser asks first,
        and this server never says yes. The origin and fetch-site checks are the same rule again, for browsers that say.
        """
        port = self.server.server_address[1]
        origin = self.headers.get('Origin')
        site = self.headers.get('Sec-Fetch-Site')
        return (self.headers.get('X-Replay-Viewer') == '1'
                and (self.headers.get('Content-Type') or '').split(';')[0].strip() == 'application/json'
                and (origin is None or origin.lower() in ('http://127.0.0.1:%d' % port, 'http://localhost:%d' % port))
                and (site is None or site == 'same-origin'))

    def do_GET(self):
        if not self.local_request():
            return
        split = urllib.parse.urlsplit(self.path)
        path = urllib.parse.unquote(split.path)
        query = urllib.parse.parse_qs(split.query)
        if path in ('/', '/index.html', '/replay.html'):
            self.send_bytes(PAGE.read_bytes(), 'text/html; charset=utf-8')
        elif path in ('/league', '/league.html'):
            self.send_bytes(LEAGUE_PAGE.read_bytes(), 'text/html; charset=utf-8')
        elif path == '/api/league':
            self.send_json({'runs': league_runs()}, etag=True)
        elif path.startswith('/api/league/') and path.count('/') == 3:
            model = (query.get('model') or ['-'])[0]
            standings = league(path.split('/')[3], int(model) if model.isdigit() else None)
            if standings is None:
                self.send_error(404, 'No league in that run')
            else:
                self.send_json(standings)
        elif path.startswith('/api/matchups/') and path.count('/') == 3:
            self.send_json(matchups(path.split('/')[3]), etag=True)
        elif path == '/api/ping':
            self.send_json({'app': APP, 'version': VERSION, 'root': str(ROOT), 'pid': os.getpid(), 'textures': TEXTURES.describe()})
        elif path == '/api/blocks':
            self.send_json({'textures': TEXTURES.blocks()})
        elif path == '/api/entities':
            self.send_json({'textures': TEXTURES.entities()})
        elif path.startswith('/mc/'):
            wanted = path[len('/mc/'):]
            body = TEXTURES.read(wanted)
            if body is None:
                # Named, because the page now asks only for textures /api/blocks and /api/entities said are there: a miss
                # is a mapping the page got wrong, and a bare "No such texture" in the log said nothing about which.
                self.send_error(404, 'No such texture in the Minecraft jar: %s' % wanted[:200])
            else:
                self.send_bytes(body, 'image/png', {'Cache-Control': 'max-age=3600'})
        elif path == '/skin/agent.png':
            if AGENT_SKIN.is_file():
                self.send_file(AGENT_SKIN, 'image/png')
            else:
                self.send_error(404, 'No agent skin at %s' % AGENT_SKIN)
        elif path == '/api/runs':
            # The one answer that is kept between requests: see listing_body.
            self.send_json_bytes(listing_body(), etag=True)
        elif path.startswith('/api/replay/') and path.count('/') == 4:
            _, _, _, run, file = path.split('/')
            file_path = replay_path(run, file)
            if file_path:
                self.send_file(file_path, 'application/json')
            else:
                self.send_error(404, 'No such replay')
        elif path.startswith('/models/') and path.count('/') == 2:
            file_path = mob_model_path(path[len('/models/'):])
            if file_path:
                self.send_file(file_path, 'application/json')
            else:
                # Not an error worth a line in the log: the page asks the index which mobs are there before asking for
                # one, so a miss here is a checkout whose viewer/models has not been written yet.
                self.send_error(404, 'No such mob shape')
        elif path.startswith('/vendor/') and path.count('/') == 2:
            file_path = vendor_path(path[len('/vendor/'):])
            if file_path:
                self.send_file(file_path, VENDOR_TYPES.get(file_path.suffix.lower(), 'text/plain; charset=utf-8'))
            else:
                self.send_error(404)
        elif path == '/favicon.ico':
            self.send_response(204)
            self.end_headers()
        else:
            self.send_error(404)

    def do_POST(self):
        if not self.local_request():
            return
        if urllib.parse.urlsplit(self.path).path != '/api/delete':
            self.send_error(404)
            return
        if not self.from_the_page():
            self.send_error(403, 'Only the viewer page may delete replays')
            return
        try:
            length = int(self.headers.get('Content-Length') or 0)
            request = json.loads(self.rfile.read(length).decode('utf-8')) if 0 < length <= 4 << 20 else None
        except (ValueError, UnicodeDecodeError):
            request = None
        if not isinstance(request, dict):
            self.send_error(400, 'Expected a JSON object')
            return
        result = delete_replays(request)
        if result['deleted'] or result['failed']:
            print('%s  Deleted %d replay%s%s' % (time.strftime('%H:%M:%S'), result['deleted'], '' if result['deleted'] == 1 else 's',
                                                '; could not delete %d' % len(result['failed']) if result['failed'] else ''))
        self.send_json(result)

    def send_bytes(self, body, content_type, extra=None):
        headers = {'Cache-Control': 'no-store'}
        headers.update(extra or {})
        self.send_response(200)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(body)))
        for name, value in headers.items():
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(body)

    def send_json(self, value, etag=False):
        self.send_json_bytes(json.dumps(value, separators=(',', ':')).encode('utf-8'), etag)

    def send_json_bytes(self, body, etag=False):
        if not etag:
            self.send_bytes(body, 'application/json')
            return
        # The page polls every few seconds; an unchanged list costs a 304 and no body.
        tag = '"%s"' % hashlib.sha1(body).hexdigest()[:20]
        if self.headers.get('If-None-Match') == tag:
            self.send_response(304)
            self.send_header('ETag', tag)
            self.end_headers()
            return
        self.send_bytes(body, 'application/json', {'ETag': tag})

    def send_file(self, path, content_type):
        with open(path, 'rb') as f:
            size = os.fstat(f.fileno()).st_size
            self.send_response(200)
            self.send_header('Content-Type', content_type)
            self.send_header('Content-Length', str(size))
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            shutil.copyfileobj(f, self.wfile)

    def log_request(self, code='-', size='-'):
        pass    # the page polls; only errors are worth a line

    def log_error(self, format, *args):
        sys.stderr.write('%s  %s\n' % (time.strftime('%H:%M:%S'), format % args))


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = False     # on Windows address reuse would let a second server share the port

    def server_bind(self):
        if hasattr(socket, 'SO_EXCLUSIVEADDRUSE'):
            self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        # Skips HTTPServer's own server_bind, whose host name lookup can take seconds on Windows.
        socketserver.TCPServer.server_bind(self)
        self.server_name, self.server_port = self.server_address[:2]

    def handle_error(self, request, client_address):
        if isinstance(sys.exc_info()[1], (ConnectionError, TimeoutError)):
            return      # the browser gave up on a request, e.g. by switching replays mid-download
        super().handle_error(request, client_address)


def running_port():
    """The port of a viewer already serving this repository with this version's features, or None."""
    ports = []
    try:
        ports.append(int(json.loads(LOCK.read_text(encoding='utf-8'))['port']))
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        pass    # no lock, or none of ours: the default port is tried anyway
    if DEFAULT_PORT not in ports:
        ports.append(DEFAULT_PORT)
    direct = urllib.request.build_opener(urllib.request.ProxyHandler({}))   # never through a proxy
    for port in ports:
        try:
            with direct.open('http://127.0.0.1:%d/api/ping' % port, timeout=1.5) as response:
                info = json.load(response)
            if info.get('app') == APP and same_path(info.get('root', ''), ROOT):
                if info.get('version', 1) >= VERSION:
                    return port
                # An older build of this same viewer for this same repository: it is replaced rather than left running
                # beside the new one, where every later start would find it again and say so. The oldest builds do not
                # say who they are, and are left alone.
                if info.get('pid'):
                    try:
                        os.kill(int(info['pid']), signal.SIGTERM)
                        print('Stopped an older replay viewer on port %d.' % port)
                        continue
                    except (OSError, ValueError):
                        pass
                print('An older replay viewer is running on port %d; starting a new one. Close its window when done.' % port)
        except (OSError, ValueError):
            pass
    return None


def bind(preferred):
    """A server on the preferred port, else the next few, else any free one."""
    ports = [preferred] + [p for p in range(DEFAULT_PORT, DEFAULT_PORT + 10) if p != preferred] + [0]
    for port in ports:
        try:
            return Server(('127.0.0.1', port), Handler)
        except OSError:
            continue
    raise SystemExit('Found no free port to listen on.')


def address(port, run, league_page=False):
    url = 'http://127.0.0.1:%d/%s' % (port, 'league' if league_page else '')
    return url + ('?run=' + urllib.parse.quote(run) if run else '')


def serve(args):
    port = running_port()
    if port:
        url = address(port, args.run, args.league)
        print('The replay viewer is already running: ' + url)
        if not args.no_browser:
            webbrowser.open(url)
        return

    global TEXTURES
    if args.minecraft_jar:
        jar = Path(args.minecraft_jar).resolve()
        version = jar_version(jar)
        if version:
            TEXTURES = Textures(jar, version, 'given with --minecraft-jar')
        else:
            print('%s has no Minecraft textures; the 3D view will draw boxes.' % jar)
    else:
        jar, version = find_minecraft_jar()
        if jar:
            TEXTURES = Textures(jar, version, 'found in the Gradle cache')

    server = bind(args.port or DEFAULT_PORT)
    port = server.server_address[1]
    if OWN_RUNS:
        RUNS.mkdir(exist_ok=True)
        LOCK.write_text(json.dumps({'app': APP, 'port': port, 'pid': os.getpid()}), encoding='utf-8')
    url = address(port, args.run, args.league)
    print('Replay viewer: ' + url)
    print('Lists every %s. Ctrl+C or closing this window stops it.' % os.path.join(str(RUNS), '*', 'replays'))
    if not OWN_RUNS:
        print('This checkout has no runs of its own, so it reads the main checkout\'s and writes nothing there.')
    print('League standings of every run with one: %s' % address(port, '', True))
    if TEXTURES.zip:
        print('Mob and block textures from Minecraft %s, %s: %s' % (TEXTURES.version, TEXTURES.how, TEXTURES.jar))
    elif not args.minecraft_jar:
        print('No Minecraft client jar in the Gradle cache, so the 3D view draws boxes and map colours. Build the mod '
              'once, or pass --minecraft-jar PATH.')
    if not args.no_browser:
        threading.Timer(0.3, webbrowser.open, [url]).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        try:
            if LOCK and json.loads(LOCK.read_text(encoding='utf-8')).get('pid') == os.getpid():
                LOCK.unlink()
        except (OSError, ValueError):
            pass


def export(args):
    """Bakes the newest replays into a copy of the page that works from disk, without the server."""
    if args.path:
        source = Path(args.path).resolve()
    elif args.run:
        source = RUNS / args.run / 'replays'
    else:
        folders = [r for r in list_runs() if r['replays']]
        if not folders:
            raise SystemExit('No replays under %s' % os.path.join(str(RUNS), '*', 'replays'))
        source = RUNS / folders[0]['name'] / 'replays'
    if source.is_file():
        files, folder = [source], source.parent
    elif source.is_dir():
        files, folder = [p for p in source.iterdir() if p.is_file() and is_replay_name(p.name)], source
    else:
        raise SystemExit('No such file or folder: %s' % source)
    files = sorted(files, key=lambda p: p.stat().st_mtime, reverse=True)[:max(1, args.count)]

    texts = []
    for path in files:
        text = path.read_text(encoding='utf-8-sig').strip()
        try:
            replay = json.loads(text)
        except ValueError as error:
            print('Skipping %s: %s' % (path.name, error))
            continue
        if not isinstance(replay, dict) or 'blocks' not in replay:
            print('Skipping %s: the old replay format, without blocks, which the page no longer draws' % path.name)
            continue
        texts.append(text)
    if not texts:
        raise SystemExit('No readable replays in %s' % source)

    page = PAGE.read_text(encoding='utf-8')
    if page.count(PLACEHOLDER) != 1:
        raise SystemExit('%s has no placeholder for replays' % PAGE)
    # three.js goes in too, so the copy's 3D view needs nothing next to it. It is placed first: replays are data
    # from the game and never touch the three.js placeholder, the other way round is less certain.
    three = VENDOR / 'three.cjs'
    if page.count(THREE_PLACEHOLDER) == 1 and three.is_file():
        page = page.replace(THREE_PLACEHOLDER, three.read_text(encoding='utf-8').replace('</script', '<\\/script'))
    else:
        print('No %s: the copy will have no 3D view.' % three)
    # "</" inside a <script> element could end it early; "<\/" is the same string to JSON.
    baked = page.replace(PLACEHOLDER, ('[' + ','.join(texts) + ']').replace('</', '<\\/'), 1)
    out = Path(args.export) if args.export else folder.parent / 'replays.html'
    if out.is_dir():
        out = out / 'replays.html'
    out.write_text(baked, encoding='utf-8')
    print('Wrote %d replay%s from %s to %s' % (len(texts), '' if len(texts) == 1 else 's', source, out))
    if not args.no_browser:
        webbrowser.open(out.resolve().as_uri())


def main():
    parser = argparse.ArgumentParser(description='Fight replay viewer for Modular Mob AI.')
    parser.add_argument('--run', help='open the newest replay of this run')
    parser.add_argument('--league', action='store_true', help='open the league standings rather than a replay')
    parser.add_argument('--port', type=int, default=0, help='port to try first (default %d)' % DEFAULT_PORT)
    parser.add_argument('--no-browser', action='store_true', help='do not open a browser')
    parser.add_argument('--minecraft-jar', metavar='PATH', help='a Minecraft client jar to read mob and block textures '
                        'from, instead of searching the Gradle cache')
    parser.add_argument('--export', nargs='?', const='', default=None, metavar='FILE',
                        help='write a standalone page with replays baked in, instead of serving')
    parser.add_argument('--path', help='with --export: a replay file or folder to take replays from')
    parser.add_argument('--count', type=int, default=20, help='with --export: how many of the newest replays')
    args = parser.parse_args()
    if args.export is not None:
        export(args)
    else:
        serve(args)


if __name__ == '__main__':
    main()
