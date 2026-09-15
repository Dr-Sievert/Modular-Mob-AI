"""One run, one self-contained HTML file.

    python -m dwarfsim view runs/run1.jsonl -o runs/run1.html

The log is embedded as JSON in the page. No CDN, no fetch, no server: it opens from ``file://``.
Six views over the same data -- the story, the timeline, per-agent traces, the Mind panel (what one
dwarf feels, wants, owes and remembers right now), the relationship matrix with its actual/believed
toggle, and the decision inspector, which is the one that matters: it shows every term that went
into every candidate the arbitrator considered on the tick you are looking at.
"""

import json
import os

from .log import load

TEMPLATE = r"""<title>__TITLE__</title>
<style>
:root{
  --bg:#14161a; --panel:#1b1e24; --panel2:#22262e; --line:#2e333d;
  --text:#d8dbe0; --dim:#8b929e; --accent:#e0a458; --good:#57a773; --bad:#e5534b;
  --anger:#e5534b; --fear:#a583d8; --happiness:#57a773; --grief:#4f8ac9; --health:#c9cdd4;
}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--text);
  font:13px/1.5 "Segoe UI",Roboto,Helvetica,Arial,sans-serif}
h1,h2,h3{font-weight:600;margin:0}
a{color:var(--accent)}
.wrap{max-width:1280px;margin:0 auto;padding:14px}
header{border-bottom:1px solid var(--line);background:var(--panel);position:sticky;top:0;z-index:5}
.meta{display:flex;gap:18px;align-items:baseline;flex-wrap:wrap}
.meta h1{font-size:16px;letter-spacing:.3px}
.meta .k{color:var(--dim)}
.scrub{display:flex;gap:10px;align-items:center;margin-top:10px}
.scrub input[type=range]{flex:1;accent-color:var(--accent)}
.tickbox{font-variant-numeric:tabular-nums;min-width:92px;color:var(--accent);font-weight:600}
button{background:var(--panel2);color:var(--text);border:1px solid var(--line);
  border-radius:5px;padding:4px 10px;cursor:pointer;font-size:12px}
button:hover{border-color:var(--accent)}
button.on{background:var(--accent);color:#1b1200;border-color:var(--accent);font-weight:600}
.tabs{display:flex;gap:6px;margin-top:10px;flex-wrap:wrap}
.strip{display:flex;gap:6px;margin-top:10px;flex-wrap:wrap}
.place{background:var(--panel2);border:1px solid var(--line);border-radius:5px;
  padding:4px 8px;min-width:104px}
.place b{color:var(--dim);font-size:10px;letter-spacing:.9px;display:block}
.place .who{font-size:12px;min-height:18px}
.place.danger{border-color:var(--bad)}
section{display:none}
section.on{display:block}
.panel{background:var(--panel);border:1px solid var(--line);border-radius:7px;
  padding:12px;margin-bottom:12px}
.row{display:flex;gap:12px;flex-wrap:wrap}
.col{flex:1;min-width:300px}
.story div{padding:3px 6px;border-radius:4px;cursor:pointer;border-left:2px solid transparent}
.story div:hover{background:var(--panel2);border-left-color:var(--accent)}
.story .tk{color:var(--accent);font-variant-numeric:tabular-nums}
table{border-collapse:collapse;width:100%;font-size:12px}
th,td{padding:3px 6px;text-align:left;border-bottom:1px solid var(--line);vertical-align:top}
th{color:var(--dim);font-weight:600;font-size:11px;letter-spacing:.5px}
td.n{font-variant-numeric:tabular-nums;text-align:right}
.ev{font-variant-numeric:tabular-nums}
.ev.here{background:#2a2f38}
.tag{display:inline-block;padding:0 5px;border-radius:3px;background:var(--panel2);
  color:var(--dim);font-size:10.5px;letter-spacing:.4px;border:1px solid var(--line)}
.tag.hot{color:var(--bad);border-color:#5a2b2b}
.tag.warm{color:var(--accent);border-color:#5a4526}
.tag.cool{color:var(--good);border-color:#2d4a38}
.say{color:#cfd6e4;font-style:italic}
.delta{color:var(--dim);font-size:11px}
.chart{background:var(--panel2);border-radius:5px;padding:6px}
.chart h3{font-size:11px;color:var(--dim);letter-spacing:.6px;margin-bottom:2px}
svg{display:block;width:100%;height:auto}
.legend span{font-size:10.5px;color:var(--dim);margin-right:8px}
.legend i{display:inline-block;width:8px;height:8px;border-radius:2px;margin-right:3px}
.matrix td{text-align:center;font-variant-numeric:tabular-nums;border:1px solid var(--bg)}
.matrix th{text-align:center}
.cand{border:1px solid var(--line);border-radius:6px;padding:8px;margin-bottom:8px;
  background:var(--panel2)}
.cand.won{border-color:var(--accent)}
.cand .hd{display:flex;justify-content:space-between;align-items:baseline;margin-bottom:6px}
.cand .hd b{font-size:13px}
.bar{display:grid;grid-template-columns:132px 1fr 62px;gap:8px;align-items:center;
  font-size:11.5px;padding:1px 0}
.bar .t{color:var(--dim)}
.bar .g{position:relative;height:12px;background:#171a20;border-radius:2px}
.bar .g i{position:absolute;top:0;bottom:0;border-radius:2px}
.bar .g u{position:absolute;top:-1px;bottom:-1px;left:50%;width:1px;background:var(--line)}
.bar .v{text-align:right;font-variant-numeric:tabular-nums;color:var(--dim)}
.muted{color:var(--dim)}
.pill{font-size:11px;color:var(--dim)}
select,input[type=text]{background:var(--panel2);color:var(--text);border:1px solid var(--line);
  border-radius:5px;padding:3px 6px;font-size:12px}
.meter{display:grid;grid-template-columns:96px 1fr 44px;gap:8px;align-items:center;
  font-size:11.5px;padding:1px 0}
.meter .t{color:var(--dim)}
.meter .g{position:relative;height:10px;background:#171a20;border-radius:2px;overflow:hidden}
.meter .g i{position:absolute;top:0;bottom:0;left:0;border-radius:2px;background:var(--accent)}
.meter .v{text-align:right;font-variant-numeric:tabular-nums;color:var(--dim)}
.mind h3{font-size:11px;color:var(--dim);letter-spacing:.7px;margin:10px 0 4px}
.mind .card{background:var(--panel2);border:1px solid var(--line);border-radius:6px;
  padding:8px;margin-bottom:8px}
.goal{display:flex;gap:8px;align-items:center;padding:2px 0;font-size:12px}
.goal b{min-width:150px}
.src{font-size:10px;letter-spacing:.4px;padding:0 4px;border-radius:3px;border:1px solid var(--line);
  color:var(--dim)}
.src.SUFFERED{color:var(--bad);border-color:#5a2b2b}
.src.HEARD{color:var(--grief);border-color:#2b3f5a}
.chiefbadge{color:var(--accent);border:1px solid var(--accent);border-radius:3px;
  font-size:10px;padding:0 4px;letter-spacing:.5px}
</style>

<header><div class="wrap">
  <div class="meta">
    <h1>dwarfsim &middot; <span id="m-scenario"></span></h1>
    <span><span class="k">seed</span> <b id="m-seed"></b></span>
    <span><span class="k">ticks</span> <b id="m-ticks"></b></span>
    <span><span class="k">dwarves</span> <b id="m-agents"></b></span>
    <span><span class="k">deaths</span> <b id="m-deaths"></b></span>
    <span><span class="k">hits</span> <b id="m-hits"></b></span>
    <span><span class="k">thefts</span> <b id="m-thefts"></b></span>
    <span><span class="k">feuds</span> <b id="m-feuds"></b></span>
    <span><span class="k">gossip</span> <b id="m-gossip"></b></span>
    <span><span class="k">chief</span> <b id="m-chief"></b></span>
  </div>
  <div class="scrub">
    <button id="play">&#9654;</button>
    <button id="back">&#8592;</button>
    <button id="fwd">&#8594;</button>
    <input type="range" id="tick" min="1" value="1">
    <span class="tickbox" id="ticklabel">tick 1</span>
  </div>
  <div class="strip" id="strip"></div>
  <div class="tabs" id="tabs"></div>
</div></header>

<div class="wrap">
  <section id="v-story" class="on"><div class="panel">
    <h2>What happened</h2>
    <p class="muted">Auto-narrated from the log. Click a line to jump the whole page to that tick.</p>
    <div class="story" id="story"></div>
  </div></section>

  <section id="v-timeline">
    <div class="panel">
      <div class="row" style="align-items:center">
        <label>dwarf <select id="f-agent"></select></label>
        <label>event <select id="f-type"></select></label>
        <label><input type="checkbox" id="f-chores"> show chores (work, moves, meals)</label>
        <span class="pill" id="f-count"></span>
      </div>
    </div>
    <div class="panel"><table><thead><tr><th style="width:60px">tick</th>
      <th style="width:120px">event</th><th style="width:70px">place</th><th>what</th></tr></thead>
      <tbody id="timeline"></tbody></table></div>
  </section>

  <section id="v-traces"><div class="panel">
    <h2>Traces</h2>
    <div class="legend" style="margin:4px 0 8px">
      <span><i style="background:var(--anger)"></i>anger</span>
      <span><i style="background:var(--fear)"></i>fear</span>
      <span><i style="background:var(--happiness)"></i>happiness</span>
      <span><i style="background:var(--grief)"></i>grief</span>
      <span><i style="background:var(--health)"></i>health</span>
      <span>needs are drawn dashed: hunger, thirst, fatigue, social</span>
    </div>
    <div class="row" id="traces"></div>
  </div></section>

  <section id="v-mind">
    <div class="panel">
      <div class="row" style="align-items:center">
        <div id="mind-pick"></div>
        <span class="pill" id="mind-note"></span>
      </div>
    </div>
    <div class="panel mind" id="mind"></div>
  </section>

  <section id="v-rels"><div class="panel">
    <h2>Relationships <span class="pill" id="rel-at"></span></h2>
    <div class="row" style="align-items:center;margin-bottom:8px">
      <div id="rel-pick"></div>
      <div id="rel-view"></div>
      <span class="pill" id="rel-help"></span>
    </div>
    <table class="matrix" id="matrix"></table>
  </div></section>

  <section id="v-decide">
    <div class="panel">
      <div class="row" style="align-items:center">
        <div id="dec-pick"></div>
        <span class="pill" id="dec-note"></span>
      </div>
    </div>
    <div class="panel" id="decide"></div>
  </section>
</div>

<script id="run" type="application/json">__DATA__</script>
<script>
"use strict";
var LOG = JSON.parse(document.getElementById("run").textContent);
var H = LOG.header, T = LOG.ticks, S = LOG.summary || {};
var AG = H.agents, N = AG.length;
var byId = {}; AG.forEach(function(a){ byId[a.id] = a; });
function nm(id){ return (byId[id] && byId[id].name) || ("#" + id); }
var DWARVES = AG.filter(function(a){ return !a.external; });
var LAST = T.length ? T[T.length-1].tick : 1;
var cur = 1, tab = "story", relMetric = "hatred", relView = "actual";
var decAgent = DWARVES.length ? DWARVES[0].id : 0, mindAgent = decAgent;

/* tick number -> index, and for each tick the most recent snapshot that carried relationships */
var idxOf = {}, relIdx = new Array(T.length), lastRel = -1;
for (var i = 0; i < T.length; i++) {
  idxOf[T[i].tick] = i;
  var any = false, ags = T[i].agents;
  for (var k in ags) { if (ags[k].rels) { any = true; break; } }
  if (any) lastRel = i;
  relIdx[i] = lastRel;
}
function frame(t){ var i = idxOf[t]; return i === undefined ? null : T[i]; }

/* ---------- header ---------- */
document.getElementById("m-scenario").textContent = H.scenario;
document.getElementById("m-seed").textContent = H.seed;
document.getElementById("m-ticks").textContent = LAST;
document.getElementById("m-agents").textContent = N;
document.getElementById("m-deaths").textContent = (S.deaths || []).length;
document.getElementById("m-hits").textContent = (S.fights && S.fights.hits) || 0;
document.getElementById("m-thefts").textContent = (S.thefts || []).length;
document.getElementById("m-feuds").textContent = (S.feuds || []).length;
document.getElementById("m-gossip").textContent = S.gossip || 0;
document.getElementById("m-chief").textContent = S.chief || H.chief || "none";

var TABS = [["story","Story"],["timeline","Timeline"],["traces","Traces"],["mind","Mind"],
            ["rels","Relationships"],["decide","Decision inspector"]];
var tabsEl = document.getElementById("tabs");
TABS.forEach(function(p){
  var b = document.createElement("button");
  b.textContent = p[1]; b.dataset.tab = p[0];
  b.onclick = function(){ setTab(p[0]); };
  tabsEl.appendChild(b);
});
function setTab(name){
  tab = name;
  TABS.forEach(function(p){
    document.getElementById("v" + "-" + p[0]).classList.toggle("on", p[0] === name);
  });
  Array.prototype.forEach.call(tabsEl.children, function(b){
    b.classList.toggle("on", b.dataset.tab === name);
  });
  draw();
}

var slider = document.getElementById("tick");
slider.max = LAST;
slider.oninput = function(){ setTick(+slider.value); };
document.getElementById("back").onclick = function(){ setTick(cur - 1); };
document.getElementById("fwd").onclick = function(){ setTick(cur + 1); };
var timer = null;
document.getElementById("play").onclick = function(){
  var b = this;
  if (timer) { clearInterval(timer); timer = null; b.textContent = "▶"; return; }
  b.textContent = "▉▉";
  timer = setInterval(function(){
    if (cur >= LAST) { clearInterval(timer); timer = null; b.textContent = "▶"; return; }
    setTick(cur + 1);
  }, 120);
};
document.onkeydown = function(e){
  if (e.target.tagName === "INPUT" || e.target.tagName === "SELECT") return;
  if (e.key === "ArrowLeft") setTick(cur - (e.shiftKey ? 10 : 1));
  if (e.key === "ArrowRight") setTick(cur + (e.shiftKey ? 10 : 1));
};
function setTick(t){
  cur = Math.max(1, Math.min(LAST, t));
  slider.value = cur;
  document.getElementById("ticklabel").textContent = "tick " + cur;
  draw();
}

/* ---------- the settlement strip ---------- */
var stripEl = document.getElementById("strip");
H.places.forEach(function(p){
  var d = document.createElement("div");
  d.className = "place"; d.dataset.place = p;
  d.innerHTML = "<b>" + p + "</b><div class='who'></div>";
  stripEl.appendChild(d);
});
function drawStrip(){
  var f = frame(cur); if (!f) return;
  var monster = null;
  f.events.forEach(function(e){
    if (e.type === "MONSTER_ARRIVES" || e.type === "MONSTER_ATTACK") monster = e.place;
  });
  Array.prototype.forEach.call(stripEl.children, function(d){
    var here = [];
    for (var k in f.agents) if (f.agents[k].place === d.dataset.place) here.push(nm(+k));
    d.querySelector(".who").textContent = here.join(", ");
    d.classList.toggle("danger", monster === d.dataset.place);
  });
}

/* ---------- story ---------- */
(function(){
  var el = document.getElementById("story");
  (S.story || []).forEach(function(s){
    var d = document.createElement("div");
    var txt = s.text.replace(/^tick (\d+): /, "");
    d.innerHTML = "<span class='tk'>tick " + s.tick + "</span> &middot; " + esc(txt);
    d.onclick = function(){ setTick(s.tick); setTab("timeline"); };
    el.appendChild(d);
  });
  if (!(S.story || []).length) el.innerHTML = "<p class='muted'>nothing worth telling</p>";
})();

/* ---------- timeline ---------- */
var CHORES = {WORK:1, MOVE:1, EAT:1, DRINK:1, REST:1};
(function(){
  var fa = document.getElementById("f-agent");
  fa.innerHTML = "<option value=''>everyone</option>";
  AG.forEach(function(a){ fa.innerHTML += "<option value='" + a.id + "'>" + esc(a.name) + "</option>"; });
  var types = {};
  T.forEach(function(f){ f.events.forEach(function(e){ types[e.type] = 1; }); });
  var ft = document.getElementById("f-type");
  ft.innerHTML = "<option value=''>everything</option>";
  Object.keys(types).sort().forEach(function(t){ ft.innerHTML += "<option>" + t + "</option>"; });
  fa.onchange = ft.onchange = draw;
  document.getElementById("f-chores").onchange = draw;
})();
function drawTimeline(){
  var wantAgent = document.getElementById("f-agent").value;
  var wantType = document.getElementById("f-type").value;
  var chores = document.getElementById("f-chores").checked;
  var lo = Math.max(1, cur - 60), hi = Math.min(LAST, cur + 60);
  var rows = [], shown = 0;
  for (var t = lo; t <= hi; t++) {
    var f = frame(t); if (!f) continue;
    for (var j = 0; j < f.events.length; j++) {
      var e = f.events[j];
      if (!chores && CHORES[e.type]) continue;
      if (wantType && e.type !== wantType) continue;
      if (wantAgent && String(e.actor) !== wantAgent && String(e.target) !== wantAgent) continue;
      rows.push(row(t, e)); shown++;
    }
  }
  document.getElementById("timeline").innerHTML = rows.join("") ||
    "<tr><td colspan='4' class='muted'>nothing in this window</td></tr>";
  document.getElementById("f-count").textContent =
    shown + " events between tick " + lo + " and " + hi;
}
var HOT = {HIT:1, KILL:1, STEAL:1, INSULT:1, SLUR:1, THREAT:1, MONSTER_ATTACK:1, ACCUSE:1,
           RETORT:1, REFUSE_APOLOGY:1, BROKEN_PROMISE:1, PUNISH:1};
var COOL = {PRAISE:1, APOLOGY:1, GIFT:1, HELP:1, MONSTER_SLAIN:1, PROMISE_KEPT:1,
            ACCEPT:1, IGNORE:1};
var WARM = {DEMAND:1, COMPLAIN:1, GOSSIP:1, AVOID:1, BARGAIN:1, REFUSE:1, ASK:1,
            GOAL_ADOPTED:1, GOAL_DROPPED:1, FULFIL:1};
function row(t, e){
  var cls = "ev" + (t === cur ? " here" : "");
  var tag = HOT[e.type] ? "tag hot" : (COOL[e.type] ? "tag cool"
                                      : (WARM[e.type] ? "tag warm" : "tag"));
  var bits = [];
  if (e.actor !== null && e.actor !== undefined) bits.push("<b>" + esc(nm(e.actor)) + "</b>");
  if (e.target !== null && e.target !== undefined) bits.push("&rarr; " + esc(nm(e.target)));
  if (e.about !== null && e.about !== undefined) bits.push("about " + esc(nm(e.about)));
  if (e.goal) bits.push("<span class='tag warm'>" + esc(e.goal) + "</span>" +
                        (e.strength !== undefined ? " " + e.strength : ""));
  if (e.ask) bits.push("<span class='muted'>" + esc(askText(e.ask)) + "</span>");
  if (e.mode) bits.push(e.mode + (e.fine ? " " + e.fine + " gold" : ""));
  if (e.want !== undefined) bits.push("wants " + e.want + " gold");
  if (e.paid) bits.push(e.paid + " gold paid");
  if (e.sal !== undefined) bits.push("<span class='muted'>salience " + e.sal + "</span>");
  if (e.text) bits.push("<span class='say'>&ldquo;" + esc(e.text) + "&rdquo;</span>");
  if (e.damage !== undefined) bits.push("dmg " + e.damage + (e.health !== undefined ? ", hp " + e.health : ""));
  if (e.item) bits.push(e.count + " " + e.item + (e.seen ? " (seen)" : " (unnoticed)"));
  if (e.got) bits.push("+" + e.got);
  if (e.kind) bits.push(e.kind.toLowerCase());
  if (e["for"]) bits.push("<span class='muted'>for " + esc(e["for"]) + "</span>");
  if (e.act) bits.push("<span class='tag'>" + esc(e.act) + "</span>");
  if (e.deltas && e.deltas.length) {
    var ds = e.deltas.slice(0, 6).map(function(d){
      return esc(nm(d.who)) + " " + fieldName(d.f) + " " + d.from.toFixed(2) + "&rarr;" + d.to.toFixed(2);
    });
    bits.push("<div class='delta'>" + ds.join(" &middot; ") +
      (e.deltas.length > 6 ? " &middot; +" + (e.deltas.length - 6) + " more" : "") + "</div>");
  }
  return "<tr class='" + cls + "'><td class='n'>" + t + "</td><td><span class='" + tag + "'>" +
    e.type + "</span></td><td class='muted'>" + (e.place || "") + "</td><td>" +
    bits.join(" ") + "</td></tr>";
}
function fieldName(f){
  if (f.indexOf("rel:") !== 0) return f;
  var p = f.split(":");
  return p[2] + "&nbsp;of&nbsp;" + esc(nm(p[1]));
}
function askText(a){
  if (!a) return "";
  var bits = [a.action];
  if (a.item) bits.push(a.quantity + "x " + a.item);
  if (a.place) bits.push("@" + a.place);
  if (a.target !== null && a.target !== undefined) bits.push("\u2192 " + nm(a.target));
  if (a.payment) bits.push("for " + a.payment + " gold");
  return bits.join(" ");
}

/* ---------- traces ---------- */
var EMO = [["anger","var(--anger)"],["fear","var(--fear)"],
           ["happiness","var(--happiness)"],["grief","var(--grief)"]];
var NEED = [["hunger","var(--anger)"],["thirst","var(--fear)"],
            ["fatigue","var(--happiness)"],["social","var(--grief)"]];
/* What each injury is called out loud. Same words the story uses. */
var INJURY_WORD = {bruised:"bruises", cut:"a cut", black_eye:"a black eye",
                   sprained_hand:"a sprained hand", broken_arm:"a broken arm",
                   broken_leg:"a broken leg", concussion:"a cracked head",
                   cracked_ribs:"cracked ribs", bleeding:"a bleeding wound"};
function injuryWords(list){
  return list.slice().sort(function(a,b){ return b.severity - a.severity; })
             .slice(0, 3).map(function(i){ return INJURY_WORD[i.kind] || i.kind; })
             .join(" and ");
}
var W = 600, HH = 96, STEP = Math.max(1, Math.ceil(T.length / 700));
function series(aid, get){
  var pts = [];
  for (var i = 0; i < T.length; i += STEP) {
    var s = T[i].agents[aid];
    if (!s) { if (pts.length) break; else continue; }
    var x = (T[i].tick / LAST) * W, y = HH - Math.max(0, Math.min(1, get(s))) * HH;
    pts.push(x.toFixed(1) + "," + y.toFixed(1));
  }
  return pts.join(" ");
}
function chart(aid, title, lines, dashed){
  var svg = "<svg viewBox='0 0 " + W + " " + HH + "' preserveAspectRatio='none'>";
  svg += "<line x1='0' y1='" + (HH/2) + "' x2='" + W + "' y2='" + (HH/2) +
         "' stroke='#2b303a' stroke-width='1'/>";
  lines.forEach(function(l){
    svg += "<polyline fill='none' stroke='" + l[1] + "' stroke-width='1.4' " +
      (dashed ? "stroke-dasharray='3 2' " : "") + "points='" + series(aid, l[2]) + "'/>";
  });
  svg += "<line class='cursor' x1='0' y1='0' x2='0' y2='" + HH +
         "' stroke='var(--accent)' stroke-width='1'/></svg>";
  return "<div class='chart'><h3>" + title + "</h3>" + svg + "</div>";
}
(function(){
  var el = document.getElementById("traces");
  DWARVES.forEach(function(a){
    var emo = EMO.map(function(e){
      return [e[0], e[1], function(s){ return s.emotions[e[0]]; }];
    });
    emo.push(["health","var(--health)", function(s){ return s.health / 20; }]);
    var need = NEED.map(function(e){
      return [e[0], e[1], function(s){ return s.needs[e[0]]; }];
    });
    var d = document.createElement("div");
    d.className = "col";
    d.innerHTML = "<div class='panel'><h3 style='font-size:13px;color:var(--text)'>" +
      esc(a.name) + " <span class='pill'>bravery " + a.traits.bravery +
      " &middot; greed " + a.traits.greed + " &middot; temper " + a.traits.temper +
      " &middot; sociability " + a.traits.sociability + "</span></h3>" +
      chart(a.id, "emotions and health", emo, false) +
      chart(a.id, "needs", need, true) + "</div>";
    el.appendChild(d);
  });
})();
function drawCursor(){
  var x = (cur / LAST) * W;
  Array.prototype.forEach.call(document.querySelectorAll(".cursor"), function(l){
    l.setAttribute("x1", x); l.setAttribute("x2", x);
  });
}

/* ---------- relationships ---------- */
(function(){
  var el = document.getElementById("rel-pick");
  ["trust","respect","hatred"].forEach(function(m){
    var b = document.createElement("button");
    b.textContent = m; b.dataset.m = m;
    b.onclick = function(){ relMetric = m; draw(); };
    el.appendChild(b);
  });
  var vw = document.getElementById("rel-view");
  [["actual","actual"],["believed","believed by others"]].forEach(function(p){
    var b = document.createElement("button");
    b.textContent = p[1]; b.dataset.v = p[0];
    b.onclick = function(){ relView = p[0]; draw(); };
    vw.appendChild(b);
  });
})();
function relColour(v, metric){
  if (v === null) return "background:#1a1d23;color:#555";
  var a = Math.min(1, Math.abs(v));
  if (metric === "hatred") return "background:rgba(229,83,75," + (a*0.85).toFixed(2) + ")";
  var c = v >= 0 ? "87,167,115" : "229,83,75";
  return "background:rgba(" + c + "," + (a*0.85).toFixed(2) + ")";
}
function drawMatrix(){
  Array.prototype.forEach.call(document.getElementById("rel-pick").children, function(b){
    b.classList.toggle("on", b.dataset.m === relMetric);
  });
  Array.prototype.forEach.call(document.getElementById("rel-view").children, function(b){
    b.classList.toggle("on", b.dataset.v === relView);
  });
  var i = idxOf[cur];
  var src = (i === undefined || relIdx[i] < 0) ? null : T[relIdx[i]];
  document.getElementById("rel-at").textContent = src ? ("as at tick " + src.tick) : "no data";
  document.getElementById("rel-help").textContent = relView === "actual"
    ? "row = how the dwarf on the left feels about the dwarf on top"
    : "reputation: what everyone who holds an opinion currently believes, averaged";
  var alive = {}; var f = frame(cur);
  if (f) for (var k in f.agents) alive[k] = 1;
  var slot = {trust:0, respect:1, hatred:2}[relMetric];

  if (relView === "believed") {
    var rep = (src && src.rep) || {};
    var h = "<tr><th></th><th>trust</th><th>respect</th><th>hatred</th>" +
            "<th>held by</th><th style='width:40%'></th></tr>";
    AG.forEach(function(a){
      var r = rep[a.id];
      h += "<tr><th style='text-align:right'>" + esc(a.name) +
           (a.external ? " <span class='pill'>(player)</span>" : (alive[a.id] ? "" : " &dagger;")) +
           "</th>";
      if (!r) { h += "<td colspan='5' class='muted'>nobody has an opinion</td></tr>"; return; }
      [0,1,2].forEach(function(j){
        h += "<td style='" + relColour(r[j], j === 2 ? "hatred" : "trust") + "'>" +
             r[j].toFixed(2) + "</td>";
      });
      h += "<td class='muted'>" + r[3] + "</td>";
      var v = r[slot], w = Math.min(1, Math.abs(v)) * 100;
      h += "<td><span style='display:inline-block;width:100%;height:10px;background:#171a20;" +
           "border-radius:2px;position:relative;overflow:hidden'><i style='position:absolute;" +
           "top:0;bottom:0;left:0;width:" + w.toFixed(0) + "%;" +
           relColour(v, relMetric) + "'></i></span></td></tr>";
    });
    document.getElementById("matrix").innerHTML = h;
    return;
  }

  var h = "<tr><th></th>";
  AG.forEach(function(a){ h += "<th>" + esc(a.name.slice(0,6)) + "</th>"; });
  h += "</tr>";
  AG.forEach(function(a){
    h += "<tr><th style='text-align:right'>" + esc(a.name) +
         (a.external ? " <span class='pill'>(player)</span>" : (alive[a.id] ? "" : " &dagger;")) +
         "</th>";
    AG.forEach(function(b){
      if (String(a.id) === String(b.id)) { h += "<td style='background:#1a1d23'></td>"; return; }
      var v = null;
      if (src && src.agents[a.id] && src.agents[a.id].rels && src.agents[a.id].rels[b.id])
        v = src.agents[a.id].rels[b.id][slot];
      h += "<td style='" + relColour(v, relMetric) + "'>" +
           (v === null ? "&middot;" : v.toFixed(2)) + "</td>";
    });
    h += "</tr>";
  });
  document.getElementById("matrix").innerHTML = h;
}


/* ---------- mind panel ---------- */
/* Goals, memories and open obligations ride on the same slow snapshot as relationships, so the
   panel shows the most recent one at or before the tick you are looking at, and says which. */
(function(){
  var el = document.getElementById("mind-pick");
  DWARVES.forEach(function(a){
    var b = document.createElement("button");
    b.textContent = a.name; b.dataset.a = a.id;
    b.onclick = function(){ mindAgent = a.id; draw(); };
    el.appendChild(b);
  });
})();
function meter(label, v, colour){
  var w = Math.max(0, Math.min(1, v)) * 100;
  return "<div class='meter'><span class='t'>" + esc(label) + "</span>" +
    "<span class='g'><i style='width:" + w.toFixed(1) + "%;background:" + (colour || "var(--accent)") +
    "'></i></span><span class='v'>" + v.toFixed(2) + "</span></div>";
}
var GOAL_WORDS = {GET_RICH:"get rich", AVENGE:"avenge", PROTECT:"stand over",
                  REPAY:"repay", BEFRIEND:"make a friend of", KEEP_PEACE:"keep the peace"};
function goalText(g){
  var w = GOAL_WORDS[g.kind] || g.kind;
  return (g.target === undefined || g.target === null) ? w : w + " " + nm(g.target);
}
function drawMind(){
  Array.prototype.forEach.call(document.getElementById("mind-pick").children, function(b){
    b.classList.toggle("on", String(b.dataset.a) === String(mindAgent));
  });
  var f = frame(cur), i = idxOf[cur];
  var slow = (i === undefined || relIdx[i] < 0) ? null : T[relIdx[i]];
  var now = f && f.agents[mindAgent];
  var mind = slow && slow.agents[mindAgent];
  var who = byId[mindAgent] || {};
  if (!now) {
    document.getElementById("mind").innerHTML =
      "<p class='muted'>" + esc(nm(mindAgent)) + " is not in the settlement on tick " + cur + ".</p>";
    document.getElementById("mind-note").textContent = "";
    return;
  }
  var injuries = now.injuries || [];
  document.getElementById("mind-note").textContent =
    "tick " + cur + " at the " + now.place + ", " + now.health.toFixed(1) + " hp" +
    (injuries.length ? ", " + injuryWords(injuries) : ", unhurt") +
    (mind ? "; goals, memories and obligations as at tick " + slow.tick : "");

  var out = "<div class='row'>";
  out += "<div class='col'><div class='card'><h3>FEELS</h3>";
  EMO.forEach(function(e){ out += meter(e[0], now.emotions[e[0]], e[1]); });
  out += "<h3>NEEDS</h3>";
  NEED.forEach(function(e){ out += meter(e[0], now.needs[e[0]], e[1]); });
  out += "</div></div>";

  out += "<div class='col'><div class='card'><h3>IS" +
         (String(H.chief) === String(mindAgent) ? " <span class='chiefbadge'>CHIEF</span>" : "") +
         "</h3>";
  var traits = who.traits || {};
  Object.keys(traits).forEach(function(k){ out += meter(k, traits[k]); });
  out += "<h3>CARRIES</h3><div class='pill'>";
  out += Object.keys(now.inv).map(function(k){ return k + " " + now.inv[k]; }).join(" &middot; ");
  out += "</div>";
  // What is broken. Health says how close to dying; this says what the dwarf can still do.
  out += "<h3>CONDITION</h3>";
  if (!injuries.length) {
    out += "<p class='muted'>unhurt</p>";
  } else {
    injuries.forEach(function(i){
      out += meter(INJURY_WORD[i.kind] || i.kind, i.severity, "var(--anger)");
    });
    if (now.pain !== undefined) out += meter("pain", now.pain, "var(--grief)");
  }
  out += "</div></div>";

  out += "<div class='col'><div class='card'><h3>WANTS</h3>";
  var goals = (mind && mind.goals) || [];
  if (!goals.length) out += "<p class='muted'>nothing in particular</p>";
  goals.forEach(function(g){
    out += "<div class='goal'><b>" + esc(goalText(g)) + "</b>" +
      "<span class='g' style='flex:1;position:relative;height:10px;background:#171a20;" +
      "border-radius:2px;overflow:hidden'><i style='position:absolute;top:0;bottom:0;left:0;" +
      "width:" + (g.strength * 100).toFixed(0) + "%;background:var(--accent)'></i></span>" +
      "<span class='v' style='color:var(--dim)'>" + g.strength.toFixed(2) + "</span></div>";
  });
  out += "<h3>OWES AND IS OWED</h3>";
  var obl = (mind && mind.obl) || [];
  if (!obl.length) out += "<p class='muted'>nothing outstanding</p>";
  obl.forEach(function(o){
    var mine = String(o.to) === String(mindAgent);
    out += "<div class='pill' style='display:block;color:var(--text)'>" +
      (mine ? "owes " + esc(nm(o.from)) : esc(nm(o.to)) + " owes") + ": " +
      esc(askText(o.ask)) + " <span class='tag'>" + o.status + "</span>" +
      " <span class='muted'>by tick " + o.deadline +
      (o.paid ? ", " + o.paid + " gold paid" : "") + "</span></div>";
  });
  out += "</div></div></div>";

  out += "<div class='card'><h3>REMEMBERS</h3>";
  var mem = (mind && mind.mem) || [];
  if (!mem.length) {
    out += "<p class='muted'>nothing yet</p>";
  } else {
    out += "<table><thead><tr><th style='width:52px'>tick</th><th style='width:120px'>what</th>" +
           "<th>who</th><th style='width:64px'>place</th><th style='width:110px'>how I know</th>" +
           "<th style='width:120px'>salience</th></tr></thead><tbody>";
    mem.forEach(function(m){
      var src = "<span class='src " + m.source + "'>" + m.source +
                (m["from"] !== undefined ? " " + esc(nm(m["from"])) : "") + "</span>";
      out += "<tr><td class='n'>" + m.tick + "</td><td><span class='tag'>" + m.kind + "</span></td>" +
        "<td>" + esc(nm(m.actor)) +
        (m.target !== null && m.target !== undefined ? " &rarr; " + esc(nm(m.target)) : "") +
        (m.wit ? " <span class='muted'>(" + m.wit + " watching)</span>" : "") + "</td>" +
        "<td class='muted'>" + (m.place || "") + "</td><td>" + src + "</td>" +
        "<td><span class='g' style='display:inline-block;width:70px;height:9px;background:#171a20;" +
        "border-radius:2px;position:relative;overflow:hidden'><i style='position:absolute;top:0;" +
        "bottom:0;left:0;width:" + Math.min(100, m.sal * 100).toFixed(0) +
        "%;background:var(--bad)'></i></span> <span class='muted'>" + m.sal.toFixed(2) +
        "</span></td></tr>";
    });
    out += "</tbody></table>";
  }
  out += "</div>";
  document.getElementById("mind").innerHTML = out;
}

/* ---------- decision inspector ---------- */
(function(){
  var el = document.getElementById("dec-pick");
  DWARVES.forEach(function(a){
    var b = document.createElement("button");
    b.textContent = a.name; b.dataset.a = a.id;
    b.onclick = function(){ decAgent = a.id; draw(); };
    el.appendChild(b);
  });
})();
function drawDecide(){
  Array.prototype.forEach.call(document.getElementById("dec-pick").children, function(b){
    b.classList.toggle("on", String(b.dataset.a) === String(decAgent));
  });
  var f = frame(cur), out = "";
  var d = null;
  if (f) f.decisions.forEach(function(x){ if (String(x.agent) === String(decAgent)) d = x; });
  if (!d) {
    document.getElementById("decide").innerHTML =
      "<p class='muted'>" + esc(nm(decAgent)) + " made no decision on tick " + cur +
      " (dead, or the run ended).</p>";
    document.getElementById("dec-note").textContent = "";
    return;
  }
  document.getElementById("dec-note").textContent =
    "tick " + cur + ": chose " + d.chosen + " (score " + d.score.toFixed(2) + ")" +
    (d.moved ? ", walked to " + d.moved : "");
  var span = 0;
  d.top.forEach(function(c){
    for (var k in c.terms) span = Math.max(span, Math.abs(c.terms[k]));
  });
  span = span || 1;
  d.top.forEach(function(c){
    var terms = Object.keys(c.terms).sort(function(a, b){
      return Math.abs(c.terms[b]) - Math.abs(c.terms[a]);
    });
    out += "<div class='cand" + (c.won ? " won" : "") + "'><div class='hd'><b>" +
      esc(c.action) + "</b><span class='pill'>" + (c.won ? "chosen &middot; " : "") +
      "score " + c.score.toFixed(3) + "</span></div>";
    terms.forEach(function(t){
      var v = c.terms[t], w = (Math.abs(v) / span * 50).toFixed(1);
      var style = v >= 0 ? "left:50%;width:" + w + "%;background:var(--good)"
                         : "right:50%;width:" + w + "%;background:var(--bad)";
      out += "<div class='bar'><span class='t'>" + t + "</span>" +
        "<span class='g'><u></u><i style='" + style + "'></i></span>" +
        "<span class='v'>" + (v >= 0 ? "+" : "") + v.toFixed(3) + "</span></div>";
    });
    out += "</div>";
  });
  document.getElementById("decide").innerHTML = out;
}

/* ---------- plumbing ---------- */
function esc(s){
  return String(s).replace(/[&<>"]/g, function(c){
    return {"&":"&amp;","<":"&lt;",">":"&gt;","\"":"&quot;"}[c];
  });
}
function draw(){
  drawStrip();
  drawCursor();
  if (tab === "timeline") drawTimeline();
  if (tab === "mind") drawMind();
  if (tab === "rels") drawMatrix();
  if (tab === "decide") drawDecide();
}
setTab("story");
setTick(1);
</script>
"""


def write_html(log_path, out_path):
    """Render a JSONL run as one self-contained HTML file."""
    run = load(log_path)
    data = json.dumps(run, separators=(",", ":"))
    # The page holds the run inside a <script type="application/json">, so nothing may look like a
    # closing tag. Escaping every "<" is the cheap, complete way to be sure.
    data = data.replace("<", "\\u003c")
    header = run["header"] or {}
    title = "dwarfsim %s seed %s" % (header.get("scenario", "run"), header.get("seed", "?"))
    html = TEMPLATE.replace("__TITLE__", title).replace("__DATA__", data)
    directory = os.path.dirname(os.path.abspath(out_path))
    if directory:
        os.makedirs(directory, exist_ok=True)
    with open(out_path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(html)
    return out_path
