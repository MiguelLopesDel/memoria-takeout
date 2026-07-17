// The YouTube deep dive: report, video/channel search and the slide-in detail panel.
// YtVideoLine, YtDetailPanel and the thumbnail helper are private to this module.

import { useEffect, useState } from "react";
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip as ChartTooltip,
  XAxis,
  YAxis
} from "recharts";
import { CalendarDays, EyeOff, History, Loader2, Search, Sparkles, TrendingUp, X, Youtube } from "lucide-react";
import { readJson } from "../api";
import { compactNumber, formatDate, formatDateTime, formatDayShort, labelType, mask, monthLabel } from "../format";
import { MemoryLine, PanelTitle } from "../components/primitives";
import type { YtChannelDetail, YtReport, YtVideo, YtVideoDetail } from "../types";

// Thumbnails come straight from YouTube's public CDN by video id — no API key needed.
function ytThumb(videoId: string) {
  return `https://i.ytimg.com/vi/${videoId}/mqdefault.jpg`;
}

function YtVideoLine({ videoId, title, meta, value, interactions, privacy, onClick }: {
  videoId: string;
  title: string | null;
  meta: string;
  value: number;
  interactions: number;
  privacy: boolean;
  onClick: () => void;
}) {
  return (
    <button className="ytVideoRow" onClick={onClick} title="Ver histórico deste vídeo">
      {privacy
        ? <div className="ytThumb ytThumbHidden"><EyeOff size={16} /></div>
        : <img className="ytThumb" loading="lazy" src={ytThumb(videoId)} alt="" onError={(event) => { (event.target as HTMLImageElement).style.visibility = "hidden"; }} />}
      <div className="ytVideoBody">
        <span className="pageTitle">{mask(title || videoId, privacy)}</span>
        <span className="pageUrl">{meta}</span>
      </div>
      <span className="ytVideoMetric" title={`Aberto/assistido ${value} ${value === 1 ? "vez" : "vezes"}${interactions > 0 ? ` · ${interactions} interações suas (comentários, chat, posts)` : ""}`}>
        <strong>{value}×</strong>
        <small>assistido/aberto</small>
        {interactions > 0 && <small>{interactions} interações</small>}
      </span>
    </button>
  );
}

const CHANNEL_STATUS: Record<string, { label: string; color: string }> = {
  ativo: { label: "ainda acompanha", color: "#34d399" },
  abandonado: { label: "abandonado", color: "#fb7185" },
  novo: { label: "descoberta recente", color: "#67e8f9" }
};

export function YouTubeView({ active, query, privacy, onOpenDay }: { active: boolean; query: string; privacy: boolean; onOpenDay: (day: string) => void }) {
  const [data, setData] = useState<YtReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [videoSearch, setVideoSearch] = useState("");
  const [videoRows, setVideoRows] = useState<YtVideo[] | null>(null);
  const [videoLoading, setVideoLoading] = useState(false);
  const [videoDetail, setVideoDetail] = useState<YtVideoDetail | null>(null);
  const [channelDetail, setChannelDetail] = useState<YtChannelDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  useEffect(() => {
    if (!active) return;
    let alive = true;
    setLoading(true);
    fetch(`/api/youtube?${query}&limit=30`)
      .then(readJson)
      .then((payload) => { if (alive) setData(payload); })
      .catch(() => {})
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [active, query]);

  useEffect(() => {
    if (!active) return;
    if (!videoSearch.trim()) { setVideoRows(null); return; }
    let alive = true;
    setVideoLoading(true);
    const id = window.setTimeout(() => {
      fetch(`/api/youtube/videos?${query}&search=${encodeURIComponent(videoSearch.trim())}&limit=40`)
        .then(readJson)
        .then((rows) => { if (alive) setVideoRows(Array.isArray(rows) ? rows : []); })
        .catch(() => {})
        .finally(() => { if (alive) setVideoLoading(false); });
    }, 260);
    return () => { alive = false; window.clearTimeout(id); };
  }, [active, query, videoSearch]);

  async function openVideo(videoId: string) {
    setDetailLoading(true);
    setChannelDetail(null);
    try {
      setVideoDetail(await fetch(`/api/youtube/video?id=${encodeURIComponent(videoId)}`).then(readJson));
    } finally {
      setDetailLoading(false);
    }
  }

  async function openChannel(name: string) {
    setDetailLoading(true);
    setVideoDetail(null);
    try {
      setChannelDetail(await fetch(`/api/youtube/channel?name=${encodeURIComponent(name)}&${query}`).then(readJson));
    } finally {
      setDetailLoading(false);
    }
  }

  if (loading && !data) return <div className="emptyState"><Loader2 className="spin" size={18} /> Analisando seu YouTube…</div>;
  if (!data) return <div className="emptyState">Sem dados do YouTube neste recorte.</div>;

  const timeline = data.timeline.map((item) => ({ name: item.label, value: item.value }));
  const videos = videoRows ?? data.topVideos;

  return (
    <div className="siteView">
      <section className="chartPanel span2 siteHeader">
        <div>
          <div className="siteTitle"><Youtube size={18} /><h2>Seu ecossistema YouTube</h2></div>
          <p>Vídeos, YouTube Music, comentários, chats e visitas ao site, tudo junto.{loading ? " · atualizando…" : ""}</p>
        </div>
      </section>

      <section className="chartPanel span2">
        <PanelTitle icon={<Sparkles size={16} />} title="Resumo" />
        <div className="insightStrip">
          <div className="insightCard"><span>Eventos</span><strong>{compactNumber(data.summary.total)}</strong></div>
          <div className="insightCard"><span>Vídeos únicos</span><strong>{compactNumber(data.summary.videos)}</strong></div>
          <div className="insightCard"><span>Canais</span><strong>{compactNumber(data.summary.channels)}</strong></div>
          <div className="insightCard"><span>Comentários</span><strong>{compactNumber(data.summary.comments)}</strong></div>
          <div className="insightCard"><span>Dias ativos</span><strong>{compactNumber(data.summary.activeDays)}</strong></div>
          <div className="insightCard"><span>Primeira atividade</span><strong>{formatDate(data.summary.firstSeen)}</strong></div>
        </div>
      </section>

      <section className="chartPanel span2">
        <PanelTitle icon={<CalendarDays size={16} />} title="Atividade mensal" />
        <ResponsiveContainer width="100%" height={190}>
          <AreaChart data={timeline}>
            <defs>
              <linearGradient id="ytPulse" x1="0" x2="0" y1="0" y2="1">
                <stop offset="0%" stopColor="#fb7185" stopOpacity={0.7} />
                <stop offset="100%" stopColor="#fb7185" stopOpacity={0.02} />
              </linearGradient>
            </defs>
            <CartesianGrid stroke="var(--grid)" vertical={false} />
            <XAxis dataKey="name" tick={{ fill: "var(--muted)", fontSize: 11 }} tickLine={false} axisLine={false} />
            <YAxis tick={{ fill: "var(--muted)", fontSize: 11 }} tickLine={false} axisLine={false} width={42} />
            <ChartTooltip contentStyle={{ background: "var(--surface-strong)", border: "1px solid var(--border)", color: "var(--text)" }} />
            <Area type="monotone" dataKey="value" stroke="#fb7185" strokeWidth={2} fill="url(#ytPulse)" />
          </AreaChart>
        </ResponsiveContainer>
      </section>

      <section className="chartPanel span2">
        <PanelTitle icon={<Search size={16} />} title="Vídeos e músicas — quantas vezes e a primeira vez" />
        <div className="siteSearchBox">
          <Search size={16} />
          <input value={videoSearch} onChange={(event) => setVideoSearch(event.target.value)} placeholder="Buscar um vídeo, uma música ou um canal (ex.: one punch man)" />
          {videoSearch && <button className="iconButton" onClick={() => setVideoSearch("")} title="Limpar"><X size={14} /></button>}
        </div>
        <div className="pageList">
          {videoLoading && <p className="siteHint"><Loader2 className="spin" size={13} /> Buscando…</p>}
          {videos.map((video) => (
            <YtVideoLine
              key={video.videoId}
              videoId={video.videoId}
              title={video.title}
              meta={`${video.channel ? `${video.channel} · ` : ""}primeira vez ${formatDayShort(video.firstDay)} · última ${formatDayShort(video.lastDay)}`}
              value={video.value}
              interactions={video.interactions}
              privacy={privacy}
              onClick={() => openVideo(video.videoId)}
            />
          ))}
          {!videoLoading && videos.length === 0 && <p className="siteHint">{videoSearch ? "Nada encontrado para essa busca." : "Nenhum vídeo neste recorte."}</p>}
        </div>
      </section>

      <section className="chartPanel">
        <PanelTitle icon={<TrendingUp size={16} />} title="Canais que você mais viu" />
        <div className="rankList">
          {data.topChannels.map((channel) => (
            <button key={channel.channel} onClick={() => openChannel(channel.channel)} title="Ver a história deste canal">
              <span>{mask(channel.channel, privacy)}</span>
              <strong>{compactNumber(channel.value)}</strong>
            </button>
          ))}
          {data.topChannels.length === 0 && <p className="siteHint">Nenhum canal identificado neste recorte.</p>}
        </div>
      </section>

      <section className="chartPanel">
        <PanelTitle icon={<History size={16} />} title="Trajetória — acompanhou e abandonou" />
        <div className="phaseList">
          {data.channelPhases.map((phase) => {
            const peakMax = Math.max(1, ...phase.series.map((point) => point.value));
            const status = CHANNEL_STATUS[phase.status] ?? CHANNEL_STATUS.ativo;
            return (
              <div key={phase.label} className="phaseRow">
                <div className="phaseHead">
                  <strong>{mask(phase.label, privacy)}</strong>
                  <span>{phase.total.toLocaleString("pt-BR")}</span>
                </div>
                <div className="phaseStatusLine">
                  <span className="siteTag" style={{ color: status.color, borderColor: status.color }}>{status.label}</span>
                  <span>{phase.first ? monthLabel(phase.first) : ""} → {phase.last ? monthLabel(phase.last) : ""}</span>
                </div>
                <div className="phaseSpark">
                  {phase.series.map((point) => (
                    <div key={point.ym} className="phaseBar" title={`${point.ym}: ${point.value.toLocaleString("pt-BR")}`} style={{ height: `${6 + (point.value / peakMax) * 34}px`, opacity: point.ym === phase.peak ? 1 : 0.5 }} />
                  ))}
                </div>
              </div>
            );
          })}
          {data.channelPhases.length === 0 && <p className="siteHint">Sem canais suficientes.</p>}
        </div>
      </section>

      {(videoDetail || channelDetail || detailLoading) && (
        <YtDetailPanel
          videoDetail={videoDetail}
          channelDetail={channelDetail}
          loading={detailLoading}
          privacy={privacy}
          onOpenChannel={openChannel}
          onOpenDay={onOpenDay}
          onClose={() => { setVideoDetail(null); setChannelDetail(null); }}
        />
      )}
    </div>
  );
}

// Slide-in detail for one video (every encounter, year by year) or one channel (monthly
// rhythm and most-watched videos). Reuses the DayPanel overlay chrome.
function YtDetailPanel({ videoDetail, channelDetail, loading, privacy, onOpenChannel, onOpenDay, onClose }: {
  videoDetail: YtVideoDetail | null;
  channelDetail: YtChannelDetail | null;
  loading: boolean;
  privacy: boolean;
  onOpenChannel: (name: string) => void;
  onOpenDay: (day: string) => void;
  onClose: () => void;
}) {
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => { if (event.key === "Escape") onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const title = videoDetail ? (videoDetail.summary.title || videoDetail.videoId) : channelDetail?.channel ?? "";
  return (
    <div className="dayOverlay" onClick={onClose}>
      <aside className="dayPanel" onClick={(event) => event.stopPropagation()}>
        <header className="dayPanelHead">
          <div>
            <h3><Youtube size={17} /> {mask(title, privacy)}</h3>
            {videoDetail && (
              <small>
                assistido/aberto {videoDetail.summary.views.toLocaleString("pt-BR")} {videoDetail.summary.views === 1 ? "vez" : "vezes"}
                {videoDetail.summary.interactions > 0 ? ` · ${videoDetail.summary.interactions.toLocaleString("pt-BR")} interações suas` : ""}
                {" "}· {videoDetail.summary.days.toLocaleString("pt-BR")} dias
                {videoDetail.summary.firstSeen ? ` · primeira vez ${formatDateTime(videoDetail.summary.firstSeen)}` : ""}
              </small>
            )}
            {channelDetail && (
              <small>
                {channelDetail.summary.total.toLocaleString("pt-BR")} eventos · {channelDetail.summary.videos.toLocaleString("pt-BR")} vídeos · {channelDetail.summary.days.toLocaleString("pt-BR")} dias
                {channelDetail.summary.firstSeen ? ` · desde ${formatDate(channelDetail.summary.firstSeen)}` : ""}
              </small>
            )}
          </div>
          <button className="iconButton" onClick={onClose} title="Fechar (Esc)"><X size={16} /></button>
        </header>
        <div className="dayPanelBody">
          {loading && <div className="emptyState"><Loader2 className="spin" size={18} /> Carregando…</div>}

          {!loading && videoDetail && (
            <>
              {!privacy && (
                <img
                  className="ytThumbBanner"
                  src={ytThumb(videoDetail.videoId)}
                  alt=""
                  onError={(event) => { (event.target as HTMLImageElement).style.display = "none"; }}
                />
              )}
              <div className="insightStrip">
                {videoDetail.summary.channel && (
                  <button className="insightCard" onClick={() => onOpenChannel(videoDetail.summary.channel!)} title="Ver o canal" style={{ cursor: "pointer" }}>
                    <span>Canal</span><strong>{mask(videoDetail.summary.channel, privacy)}</strong>
                  </button>
                )}
                <button
                  className="insightCard"
                  disabled={!videoDetail.summary.firstDay}
                  onClick={() => videoDetail.summary.firstDay && onOpenDay(videoDetail.summary.firstDay)}
                  title="Abrir esse dia inteiro"
                  style={{ cursor: videoDetail.summary.firstDay ? "pointer" : "default" }}
                >
                  <span>Primeira vez</span><strong>{formatDateTime(videoDetail.summary.firstSeen)}</strong>
                </button>
                <button
                  className="insightCard"
                  disabled={!videoDetail.summary.lastDay}
                  onClick={() => videoDetail.summary.lastDay && onOpenDay(videoDetail.summary.lastDay)}
                  title="Abrir esse dia inteiro"
                  style={{ cursor: videoDetail.summary.lastDay ? "pointer" : "default" }}
                >
                  <span>Última vez</span><strong>{formatDateTime(videoDetail.summary.lastSeen)}</strong>
                </button>
              </div>
              <ResponsiveContainer width="100%" height={140}>
                <BarChart data={videoDetail.byYear.map((row) => ({ name: row.year, value: row.value }))}>
                  <XAxis dataKey="name" tick={{ fill: "var(--muted)", fontSize: 11 }} tickLine={false} axisLine={false} />
                  <YAxis tick={{ fill: "var(--muted)", fontSize: 11 }} tickLine={false} axisLine={false} width={36} />
                  <ChartTooltip contentStyle={{ background: "var(--surface-strong)", border: "1px solid var(--border)", color: "var(--text)" }} />
                  <Bar dataKey="value" fill="#fb7185" radius={[6, 6, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
              <div className="rankList">
                {videoDetail.byType.map((item) => (
                  <div className="rankStatic" key={item.label}><span>{labelType(item.label)}</span><strong>{item.value.toLocaleString("pt-BR")}</strong></div>
                ))}
              </div>
              {!privacy && (
                <a className="detailLink" href={`https://www.youtube.com/watch?v=${videoDetail.videoId}`} target="_blank" rel="noreferrer">abrir no YouTube</a>
              )}
              <div className="memoryLines">
                {videoDetail.events.map((event) => <MemoryLine key={event.id} event={event} privacy={privacy} />)}
              </div>
            </>
          )}

          {!loading && channelDetail && (
            <>
              <ResponsiveContainer width="100%" height={150}>
                <AreaChart data={channelDetail.timeline.map((item) => ({ name: item.label, value: item.value }))}>
                  <XAxis dataKey="name" tick={{ fill: "var(--muted)", fontSize: 10 }} tickLine={false} axisLine={false} />
                  <YAxis tick={{ fill: "var(--muted)", fontSize: 11 }} tickLine={false} axisLine={false} width={36} />
                  <ChartTooltip contentStyle={{ background: "var(--surface-strong)", border: "1px solid var(--border)", color: "var(--text)" }} />
                  <Area type="monotone" dataKey="value" stroke="#fb7185" strokeWidth={2} fill="#fb718533" />
                </AreaChart>
              </ResponsiveContainer>
              <div className="pageList">
                {channelDetail.topVideos.map((video) => (
                  <YtVideoLine
                    key={video.videoId}
                    videoId={video.videoId}
                    title={video.title}
                    meta={`${formatDayShort(video.firstDay)} → ${formatDayShort(video.lastDay)}`}
                    value={video.value}
                    interactions={video.interactions}
                    privacy={privacy}
                    onClick={() => { if (!privacy) window.open(`https://www.youtube.com/watch?v=${video.videoId}`, "_blank", "noreferrer"); }}
                  />
                ))}
              </div>
            </>
          )}
        </div>
      </aside>
    </div>
  );
}
