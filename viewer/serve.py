"""
Serves the fight replay viewer, viewer/replay.html, with a live list of every run's replays.

    python viewer/serve.py                  start the viewer, or find the one already running, and open the browser
    python viewer/serve.py --run imitate    ... on the newest replay of that run
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

The page can also delete replays, through POST /api/delete. Like everything else here it answers only to the page on
this machine, and it only ever deletes finished replays, *.json files straight inside runs/<name>/replays/.

Standard library only, so any Python 3.7 or newer runs it. Everything is found from this file's own location: the
repository is the folder above viewer/, and replays are runs/<name>/replays/*.json. The server listens on 127.0.0.1
only, and leaves runs/.replay-viewer.json behind while it runs so a second start opens the browser on it instead.
The 3D view's three.js is viewer/vendor/three.cjs, the official build of the version named in viewer/README.md.
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
VERSION = 4     # what this server can serve; an older one still running is not reused (1: no 3D, 2: no textures,
                # 3: no block textures and no deleting)
HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
RUNS = ROOT / 'runs'
PAGE = HERE / 'replay.html'
VENDOR = HERE / 'vendor'
AGENT_SKIN = ROOT / 'mod' / 'common' / 'src' / 'main' / 'resources' / 'assets' / 'modular_mob_ai' / 'textures' / 'entity' / 'agent.png'
MINECRAFT = '1.21.1'    # the version the mod is built against, whose jar is preferred
TEXTURE_PATH = re.compile(r'^textures/[a-z0-9_/.-]+\.png$')
LOCK = RUNS / '.replay-viewer.json'
DEFAULT_PORT = 8765
PLACEHOLDER = '__MMAI_REPLAYS__'
THREE_PLACEHOLDER = '__MMAI_THREE__'
VENDOR_TYPES = {'.cjs': 'text/javascript; charset=utf-8', '.js': 'text/javascript; charset=utf-8'}
FORMAT = 2      # the replay format version the page draws; older replays have no blocks, and are listed only to be deleted

# The few top-level fields the list shows. The recorder writes them before the blocks, so the first few kilobytes of
# a file hold them and the rest never needs reading just to list it.
_FIELD = re.compile(r'"(version|outcome|ticks|iteration|biome|worker|fight|brain|run)"\s*:\s*("(?:[^"\\]|\\.)*"|-?\d+(?:\.\d+)?|null)')
_headers = {}


def same_path(a, b):
    return os.path.normcase(os.path.realpath(str(a))) == os.path.normcase(os.path.realpath(str(b)))


def is_replay_name(name):
    """A finished replay file: the recorder writes under a temporary name first and renames at the end."""
    lower = name.lower()
    return lower.endswith('.json') and not name.startswith('.') and '.tmp' not in lower


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


def list_runs():
    """Every runs/<name>/replays folder with its replays, newest first."""
    runs, seen = [], set()
    if RUNS.is_dir():
        for run_dir in RUNS.iterdir():
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
    return runs


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
        path = urllib.parse.unquote(urllib.parse.urlsplit(self.path).path)
        if path in ('/', '/index.html', '/replay.html'):
            self.send_bytes(PAGE.read_bytes(), 'text/html; charset=utf-8')
        elif path == '/api/ping':
            self.send_json({'app': APP, 'version': VERSION, 'root': str(ROOT), 'pid': os.getpid(), 'textures': TEXTURES.describe()})
        elif path == '/api/blocks':
            self.send_json({'textures': TEXTURES.blocks()})
        elif path.startswith('/mc/'):
            body = TEXTURES.read(path[len('/mc/'):])
            if body is None:
                self.send_error(404, 'No such texture')
            else:
                self.send_bytes(body, 'image/png', {'Cache-Control': 'max-age=3600'})
        elif path == '/skin/agent.png':
            if AGENT_SKIN.is_file():
                self.send_file(AGENT_SKIN, 'image/png')
            else:
                self.send_error(404, 'No agent skin')
        elif path == '/api/runs':
            self.send_json({'runs': list_runs()}, etag=True)
        elif path.startswith('/api/replay/') and path.count('/') == 4:
            _, _, _, run, file = path.split('/')
            file_path = replay_path(run, file)
            if file_path:
                self.send_file(file_path, 'application/json')
            else:
                self.send_error(404, 'No such replay')
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
        body = json.dumps(value, separators=(',', ':')).encode('utf-8')
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
    except (OSError, ValueError, KeyError, TypeError):
        pass
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


def address(port, run):
    url = 'http://127.0.0.1:%d/' % port
    return url + ('?run=' + urllib.parse.quote(run) if run else '')


def serve(args):
    port = running_port()
    if port:
        url = address(port, args.run)
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
    RUNS.mkdir(exist_ok=True)
    LOCK.write_text(json.dumps({'app': APP, 'port': port, 'pid': os.getpid()}), encoding='utf-8')
    url = address(port, args.run)
    print('Replay viewer: ' + url)
    print('Lists every %s. Ctrl+C or closing this window stops it.' % os.path.join(str(RUNS), '*', 'replays'))
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
            if json.loads(LOCK.read_text(encoding='utf-8')).get('pid') == os.getpid():
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
