// Assuntos: user-defined interest topics with a live report and a "try before saving" mode.

import { useEffect, useState } from "react";
import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip as ChartTooltip, XAxis, YAxis } from "recharts";
import { CalendarDays, Clock, Globe2, Hash, History, Plus, Search, Sparkles, Youtube } from "lucide-react";
import { readJson } from "../api";
import { compactNumber, formatDate, formatDayShort, mask } from "../format";
import { MemoryLine, PanelTitle } from "../components/primitives";
import type { Topic, TopicReport } from "../types";

const TOPIC_SUGGESTIONS: Array<{ name: string; keywords: string }> = [
  { name: "Mangá & Manhwa", keywords: "manga, manhwa, manhua, webtoon, scan, capítulo" },
  { name: "Anime", keywords: "anime, otaku, crunchyroll, dublado, episódio" },
  { name: "Games", keywords: "gameplay, minecraft, jogo, game, steam, boss" },
  { name: "Música", keywords: "música, playlist, lyrics, mv, clipe oficial" },
  { name: "Programação", keywords: "programação, java, python, javascript, github, código" },
  { name: "Estudos", keywords: "enem, vestibular, matemática, física, resumo, aula" }
];

export function TopicsView({ active, query, privacy }: { active: boolean; query: string; privacy: boolean }) {
  const [topics, setTopics] = useState<Topic[]>([]);
  const [name, setName] = useState("");
  const [keywords, setKeywords] = useState("");
  const [selected, setSelected] = useState<{ topic: Topic | null; name: string; keywords: string } | null>(null);
  const [report, setReport] = useState<TopicReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  async function loadTopics() {
    const rows = await fetch("/api/topics").then(readJson).catch(() => null);
    setTopics(Array.isArray(rows) ? rows : []);
  }

  useEffect(() => {
    if (active) void loadTopics();
  }, [active]);

  useEffect(() => {
    if (!active || !selected) { setReport(null); return; }
    let alive = true;
    setLoading(true);
    setError("");
    fetch(`/api/topics/report?${query}&keywords=${encodeURIComponent(selected.keywords)}&limit=20`)
      .then(async (res) => {
        const payload = await readJson(res);
        if (!res.ok) throw new Error(payload?.error ?? "Falha ao analisar o assunto.");
        if (alive) setReport(payload);
      })
      .catch((err) => { if (alive) setError(err instanceof Error ? err.message : "Erro inesperado."); })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [active, query, selected?.keywords]);

  async function saveTopic(topicName: string, topicKeywords: string) {
    const clean = topicKeywords.trim();
    if (!clean) return;
    const res = await fetch("/api/topics", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name: topicName.trim() || "Assunto", keywords: clean })
    });
    const created = await readJson(res);
    setName("");
    setKeywords("");
    await loadTopics();
    if (created?.id) setSelected({ topic: created, name: created.name, keywords: created.keywords });
  }

  async function removeTopic(topic: Topic) {
    if (!window.confirm(`Excluir o assunto "${topic.name}"? Os eventos não são afetados.`)) return;
    await fetch("/api/topics/delete", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ id: topic.id })
    });
    if (selected?.topic?.id === topic.id) setSelected(null);
    await loadTopics();
  }

  const timeline = (report?.timeline ?? []).map((item) => ({ name: item.label, value: item.value }));

  return (
    <section className="organizeLayout">
      <div className="organizeSidebar">
        <div className="chartPanel">
          <PanelTitle icon={<Hash size={16} />} title="Seus assuntos" />
          <div className="inlineForm">
            <input value={name} onChange={(event) => setName(event.target.value)} placeholder="Nome (ex.: Mangá & Anime)" />
          </div>
          <div className="inlineForm">
            <input
              value={keywords}
              onChange={(event) => setKeywords(event.target.value)}
              onKeyDown={(event) => { if (event.key === "Enter") void saveTopic(name, keywords); }}
              placeholder="Palavras-chave separadas por vírgula"
            />
            <button onClick={() => void saveTopic(name, keywords)} title="Salvar assunto"><Plus size={16} /></button>
          </div>
          {keywords.trim() && (
            <button className="secondaryAction" onClick={() => setSelected({ topic: null, name: name.trim() || "Prévia", keywords })}>
              Testar sem salvar
            </button>
          )}
          <div className="orgList">
            {topics.map((topic) => (
              <button key={topic.id} className={selected?.topic?.id === topic.id ? "orgItem on" : "orgItem"} onClick={() => setSelected({ topic, name: topic.name, keywords: topic.keywords })}>
                <span className="tagDot" style={{ background: topic.color }} /> {topic.name}
              </button>
            ))}
            {topics.length === 0 && <small className="muted">Nenhum assunto salvo ainda.</small>}
          </div>
        </div>

        <div className="chartPanel">
          <PanelTitle icon={<Sparkles size={16} />} title="Sugestões" />
          <div className="orgList">
            {TOPIC_SUGGESTIONS.map((suggestion) => (
              <button key={suggestion.name} className="orgItem" title={suggestion.keywords} onClick={() => { setName(suggestion.name); setKeywords(suggestion.keywords); setSelected({ topic: null, name: suggestion.name, keywords: suggestion.keywords }); }}>
                <Plus size={13} /> {suggestion.name}
              </button>
            ))}
          </div>
          <small className="muted">Clique para testar; ajuste as palavras-chave e salve.</small>
        </div>
      </div>

      <div className="organizeMain">
        {!selected ? (
          <div className="emptyState">
            Um assunto agrupa tudo que você viu, leu, buscou e comentou sobre um tema — em todas as fontes ao mesmo tempo.
            Crie um com palavras-chave (ex.: manga, manhwa, anime) ou use uma sugestão ao lado.
          </div>
        ) : (
          <div className="siteView">
            <section className="chartPanel span2 siteHeader">
              <div>
                <div className="siteTitle"><Hash size={18} /><h2>{selected.name}</h2>{!selected.topic && <span className="siteTag">prévia</span>}</div>
                <p>{selected.keywords}{loading ? " · analisando…" : ""}</p>
              </div>
              <div className="siteRefine">
                {!selected.topic && <button className="secondaryAction" onClick={() => void saveTopic(selected.name, selected.keywords)}>Salvar assunto</button>}
                {selected.topic && <button className="linkDanger" onClick={() => void removeTopic(selected.topic!)}>Excluir</button>}
              </div>
            </section>

            {error && <div className="errorBanner span2">{error}</div>}

            {report && (
              <>
                <section className="chartPanel span2">
                  <PanelTitle icon={<Sparkles size={16} />} title="Resumo" />
                  <div className="insightStrip">
                    <div className="insightCard"><span>Eventos</span><strong>{compactNumber(report.summary.total)}</strong></div>
                    <div className="insightCard"><span>Dias ativos</span><strong>{compactNumber(report.summary.activeDays)}</strong></div>
                    <div className="insightCard"><span>Sites</span><strong>{compactNumber(report.summary.domains)}</strong></div>
                    <div className="insightCard"><span>Começou em</span><strong>{formatDate(report.summary.firstSeen)}</strong></div>
                    <div className="insightCard"><span>Último registro</span><strong>{formatDate(report.summary.lastSeen)}</strong></div>
                  </div>
                </section>

                <section className="chartPanel span2">
                  <PanelTitle icon={<CalendarDays size={16} />} title="O assunto ao longo do tempo" />
                  <ResponsiveContainer width="100%" height={180}>
                    <AreaChart data={timeline}>
                      <defs>
                        <linearGradient id="topicPulse" x1="0" x2="0" y1="0" y2="1">
                          <stop offset="0%" stopColor="#a78bfa" stopOpacity={0.7} />
                          <stop offset="100%" stopColor="#a78bfa" stopOpacity={0.02} />
                        </linearGradient>
                      </defs>
                      <CartesianGrid stroke="var(--grid)" vertical={false} />
                      <XAxis dataKey="name" tick={{ fill: "var(--muted)", fontSize: 11 }} tickLine={false} axisLine={false} />
                      <YAxis tick={{ fill: "var(--muted)", fontSize: 11 }} tickLine={false} axisLine={false} width={42} />
                      <ChartTooltip contentStyle={{ background: "var(--surface-strong)", border: "1px solid var(--border)", color: "var(--text)" }} />
                      <Area type="monotone" dataKey="value" stroke="#a78bfa" strokeWidth={2} fill="url(#topicPulse)" />
                    </AreaChart>
                  </ResponsiveContainer>
                </section>

                <section className="chartPanel">
                  <PanelTitle icon={<Globe2 size={16} />} title="Onde acontece" />
                  <div className="rankList">
                    {report.topDomains.map((item) => (
                      <div className="rankStatic" key={item.label}><span>{item.label}</span><strong>{item.value.toLocaleString("pt-BR")}</strong></div>
                    ))}
                    {report.topDomains.length === 0 && <p className="siteHint">Nenhum site neste recorte.</p>}
                  </div>
                </section>

                <section className="chartPanel">
                  <PanelTitle icon={<Youtube size={16} />} title="Canais deste assunto" />
                  <div className="rankList">
                    {report.topChannels.map((item) => (
                      <div className="rankStatic" key={item.channel}><span>{mask(item.channel, privacy)}</span><strong>{item.value.toLocaleString("pt-BR")}</strong></div>
                    ))}
                    {report.topChannels.length === 0 && <p className="siteHint">Nenhum canal identificado.</p>}
                  </div>
                </section>

                <section className="chartPanel">
                  <PanelTitle icon={<Search size={16} />} title="O que você buscou" />
                  <div className="rankList">
                    {report.searches.map((item, index) => (
                      <div className="rankStatic" key={index}><span>{privacy ? "•••" : (item.title ?? "(sem título)")}</span><strong>{item.value}×</strong></div>
                    ))}
                    {report.searches.length === 0 && <p className="siteHint">Nenhuma busca sobre o assunto.</p>}
                  </div>
                </section>

                <section className="chartPanel">
                  <PanelTitle icon={<History size={16} />} title="Páginas que marcaram" />
                  <div className="pageList">
                    {report.topPages.map((page, index) => (
                      <a key={(page.url ?? "") + index} className="pageRow" href={privacy ? undefined : page.url ?? undefined} target="_blank" rel="noreferrer" title={page.url ?? ""}>
                        <span className="pageTitle">{mask(page.title || page.url || "Sem título", privacy)}</span>
                        <span className="pageUrl">{formatDayShort(page.firstDay)} → {formatDayShort(page.lastDay)} · {page.days} dias</span>
                        <strong>{page.value}×</strong>
                      </a>
                    ))}
                    {report.topPages.length === 0 && <p className="siteHint">Nenhuma página com URL.</p>}
                  </div>
                </section>

                <section className="chartPanel span2">
                  <PanelTitle icon={<Clock size={16} />} title="Registros recentes" />
                  <div className="memoryLines">
                    {report.recent.map((event) => <MemoryLine key={event.id} event={event} privacy={privacy} />)}
                  </div>
                </section>
              </>
            )}
          </div>
        )}
      </div>
    </section>
  );
}
