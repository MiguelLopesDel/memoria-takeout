import { Download } from "lucide-react";

export function ExportPanel({ query, privacy }: { query: string; privacy: boolean }) {
  const suffix = query ? `?${query}&` : "?";
  return (
    <section className="exportGrid">
      {["json", "csv", "pdf"].map((format) => (
        <a className="exportButton" key={format} href={`/api/export${suffix}format=${format}`} target="_blank" rel="noreferrer">
          <Download size={18} /> Exportar {format.toUpperCase()}
        </a>
      ))}
      <p>{privacy ? "Modo privacidade ativo na tela. Downloads preservam os dados completos." : "Downloads usam os filtros atuais."}</p>
    </section>
  );
}
