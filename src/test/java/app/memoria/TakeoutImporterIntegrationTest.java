package app.memoria;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class TakeoutImporterIntegrationTest {
    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;

    @Test
    void importsRepresentativeTakeoutWithoutHtmlFragments() throws Exception {
        Path takeout = tempDir.resolve("Takeout");
        writeRepresentativeTakeout(takeout);

        DatabaseService database = database();
        TakeoutImporter importer =
                new TakeoutImporter(database, new ObjectMapper(), takeout.toString(), tempDir.toString());
        ImportResult result = importer.importTakeout(takeout.toString());

        assertThat(result.eventCount()).isEqualTo(7);
        assertThat(database.eventCount()).isEqualTo(7);
        assertThat(((Number) database.latestImport().get("event_count")).longValue())
                .isEqualTo(7);
        assertThat(database.quality(FilterParams.of("", "", "", "", "", "")))
                .allSatisfy(row -> assertThat(((Number) row.get("withoutTimestamp")).longValue())
                        .isZero());

        List<Map<String, Object>> search = database.events(FilterParams.of("", "Search", "", "", "", ""), 20, 0);
        assertThat(search).hasSize(1);
        assertThat(search.getFirst().get("timestamp")).isEqualTo("2025-03-19T02:46:47Z");
        assertThat(search.getFirst().get("title").toString()).contains("google baixar informações");

        List<Map<String, Object>> youtube = database.events(FilterParams.of("", "YouTube", "", "", "", ""), 20, 0);
        assertThat(youtube).extracting(row -> row.get("type")).contains("video", "comment", "chat");
        assertThat(youtube).extracting(row -> row.get("domain")).contains("youtube.com");

        List<Map<String, Object>> gmail = database.events(FilterParams.of("", "Gmail", "email", "", "", ""), 20, 0);
        assertThat(gmail).hasSize(1);
        assertThat(gmail.getFirst().get("title")).isEqualTo("Assunto de teste");

        assertThat(database.events(FilterParams.of("missing:time", "", "", "", "", ""), 20, 0))
                .isEmpty();
    }

    @Test
    void filtersSupportMultiValueNegativeAndSearchOperators() throws Exception {
        Path takeout = tempDir.resolve("Takeout");
        writeRepresentativeTakeout(takeout);

        DatabaseService database = database();
        TakeoutImporter importer =
                new TakeoutImporter(database, new ObjectMapper(), takeout.toString(), tempDir.toString());
        importer.importTakeout(takeout.toString());

        assertThat(database.events(FilterParams.of("has:time", "YouTube,Chrome", "", "", "", ""), 20, 0))
                .hasSize(4);
        assertThat(database.events(FilterParams.of("", "-YouTube", "", "", "", ""), 20, 0))
                .noneSatisfy(row -> assertThat(row.get("source")).isEqualTo("YouTube"));
        assertThat(database.events(FilterParams.of("site:youtube.com", "", "", "", "", ""), 20, 0))
                .allSatisfy(row -> assertThat(row.get("domain")).isEqualTo("youtube.com"));
        assertThat(database.events(FilterParams.of("title:google", "Search", "", "", "", ""), 20, 0))
                .hasSize(1);
        assertThat(database.events(FilterParams.of("-YouTube", "", "", "", "", ""), 20, 0))
                .noneSatisfy(
                        row -> assertThat(String.valueOf(row.get("source"))).isEqualTo("YouTube"));
        assertThat(database.events(FilterParams.of("abc-123", "", "", "", "", ""), 20, 0))
                .isNotNull();
    }

    @Test
    void reimportingIsIdempotentAndKeepsAnnotations() throws Exception {
        Path takeout = tempDir.resolve("Takeout");
        writeRepresentativeTakeout(takeout);

        DatabaseService database = database();
        TakeoutImporter importer =
                new TakeoutImporter(database, new ObjectMapper(), takeout.toString(), tempDir.toString());

        ImportResult first = importer.importTakeout(takeout.toString());
        assertThat(first.added()).isEqualTo(7);
        assertThat(first.total()).isEqualTo(7);

        // Tag one event, keyed by its (stable) id.
        long eventId = ((Number) database.events(FilterParams.of("", "Search", "", "", "", ""), 20, 0)
                        .getFirst()
                        .get("id"))
                .longValue();
        long tagId = ((Number) database.createTag(Map.of("name", "importante")).get("id")).longValue();
        database.tagEvent(eventId, tagId);

        // Simulate a database produced by the previous parser identity strategy.
        jdbc.update("UPDATE events SET event_key = 'legacy:key', source = 'Legacy source' WHERE id = ?", eventId);
        database = database();
        importer = new TakeoutImporter(database, new ObjectMapper(), takeout.toString(), tempDir.toString());

        // Re-importing the same Takeout must merge: no new events, and the tag survives.
        ImportResult second = importer.importTakeout(takeout.toString());
        assertThat(second.added()).isZero();
        assertThat(second.total()).isEqualTo(7);
        assertThat(database.eventCount()).isEqualTo(7);

        Long stillTagged = jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_tags WHERE event_id = ? AND tag_id = ?", Long.class, eventId, tagId);
        assertThat(stillTagged).isEqualTo(1);
        Long eventStillThere = jdbc.queryForObject("SELECT COUNT(*) FROM events WHERE id = ?", Long.class, eventId);
        assertThat(eventStillThere).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT source FROM events WHERE id = ?", String.class, eventId))
                .isEqualTo("Search");
    }

    @Test
    void identicalOccurrencesArePreservedAndNotDuplicatedOnReimport() throws Exception {
        Path takeout = tempDir.resolve("Takeout");
        // Three byte-identical activity records (e.g. rapid Google Lens searches in the same
        // second): distinct occurrences the source cannot tell apart. They must all survive,
        // and a re-import must not multiply them.
        write(
                takeout.resolve("Minha atividade/Google Lens/MinhaAtividade.json"),
                """
        [
          {"header":"Google Lens","title":"Pesquisou com o Google Lens","time":"2024-12-22T18:14:56.476Z"},
          {"header":"Google Lens","title":"Pesquisou com o Google Lens","time":"2024-12-22T18:14:56.476Z"},
          {"header":"Google Lens","title":"Pesquisou com o Google Lens","time":"2024-12-22T18:14:56.476Z"}
        ]
        """);

        DatabaseService database = database();
        TakeoutImporter importer =
                new TakeoutImporter(database, new ObjectMapper(), takeout.toString(), tempDir.toString());

        assertThat(importer.importTakeout(takeout.toString()).added()).isEqualTo(3);
        assertThat(database.eventCount()).isEqualTo(3);

        ImportResult second = importer.importTakeout(takeout.toString());
        assertThat(second.added()).isZero();
        assertThat(database.eventCount()).isEqualTo(3);
    }

    @Test
    void overlappingTakeoutFilesMergeTheSameExportedRecord() throws Exception {
        Path takeout = tempDir.resolve("Takeout");
        String myActivityRecord =
                """
        [{"header":"YouTube","title":"Watched Video","titleUrl":"https://www.youtube.com/watch?v=abc","time":"2026-07-09T23:38:19Z","products":["YouTube"]}]
        """;
        String historyRecord =
                """
        [{"products":["YouTube"],"time":"2026-07-09T23:38:19Z","titleUrl":"https://www.youtube.com/watch?v=abc","title":"Watched Video","header":"YouTube"}]
        """;
        write(takeout.resolve("Minha atividade/YouTube/Minhaatividade.json"), myActivityRecord);
        write(takeout.resolve("YouTube e YouTube Music/histórico/histórico-de-visualização.json"), historyRecord);

        DatabaseService database = database();
        TakeoutImporter importer =
                new TakeoutImporter(database, new ObjectMapper(), takeout.toString(), tempDir.toString());

        assertThat(importer.importTakeout(takeout.toString()).eventCount()).isEqualTo(2);
        assertThat(database.eventCount()).isEqualTo(1);
        assertThat(importer.importTakeout(takeout.toString()).added()).isZero();
        assertThat(database.eventCount()).isEqualTo(1);

        long canonicalId = jdbc.queryForObject("SELECT id FROM events", Long.class);
        jdbc.update(
                "UPDATE events SET event_key = 'legacy:first', file_path = 'Minha atividade/YouTube/Minhaatividade.json' WHERE id = ?",
                canonicalId);
        jdbc.update(
                """
        INSERT INTO events (
          event_key, timestamp, year_month, local_day, local_hour, local_weekday, source, type,
          title, text, url, domain, root_domain, file_path, raw_json
        )
        SELECT 'legacy:duplicate', timestamp, year_month, local_day, local_hour, local_weekday,
          source, type, title, text, url, domain, root_domain,
          'YouTube e YouTube Music/histórico/histórico-de-visualização.json', raw_json
        FROM events WHERE id = ?
        """,
                canonicalId);
        long duplicateId = jdbc.queryForObject("SELECT MAX(id) FROM events", Long.class);
        long tagId = ((Number)
                        database.createTag(Map.of("name", "duplicado anotado")).get("id"))
                .longValue();
        database.tagEvent(duplicateId, tagId);

        database = database();
        assertThat(database.eventCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM event_tags WHERE event_id = ? AND tag_id = ?",
                        Long.class,
                        canonicalId,
                        tagId))
                .isEqualTo(1);
    }

    @Test
    void importsCurrentMyActivityAndYouTubePostSchemas() throws Exception {
        Path takeout = tempDir.resolve("Takeout");
        write(
                takeout.resolve("Minha atividade/Chrome/Minhaatividade.json"),
                """
        [{
          "header":"example.com",
          "title":"Visited Example",
          "titleUrl":"https://www.google.com/url?q=https%3A%2F%2Fexample.com%2Fpage&usg=test",
          "time":"2026-07-10T01:44:32.836Z",
          "products":["Chrome"],
          "safeHtmlItem":[{"html":"<p>searchable response</p>"}],
          "attachedFiles":["attachment.png"]
        }]
        """);
        write(
                takeout.resolve("YouTube e YouTube Music/postagens/postagens.csv"),
                """
        ID do post,Carimbo de data/hora da atualização do post,Carimbo de data/hora da criação do post,Texto do post
        post-1,2026-05-29T19:55:42Z,2026-05-28T19:55:42Z,"{""text"":""Texto real da postagem""}"
        """);
        write(
                takeout.resolve("YouTube e YouTube Music/postagens/Configurações de comentários em postagens.csv"),
                """
        ID do post,Ativação de comentários,Moderação de comentários
        post-1,Ativar comentários,Permitir todos os comentários
        """);

        DatabaseService database = database();
        TakeoutImporter importer =
                new TakeoutImporter(database, new ObjectMapper(), takeout.toString(), tempDir.toString());
        ImportResult result = importer.importTakeout(takeout.toString());

        assertThat(result.eventCount()).isEqualTo(2);
        List<Map<String, Object>> chrome = database.events(FilterParams.of("", "Chrome", "", "", "", ""), 20, 0);
        assertThat(chrome).hasSize(1);
        assertThat(chrome.getFirst().get("domain")).isEqualTo("example.com");
        assertThat(chrome.getFirst().get("url")).isEqualTo("https://example.com/page");
        assertThat(chrome.getFirst().get("text").toString()).contains("searchable response", "attachment.png");

        List<Map<String, Object>> posts = database.events(FilterParams.of("", "YouTube", "post", "", "", ""), 20, 0);
        assertThat(posts).hasSize(1);
        assertThat(posts.getFirst().get("timestamp")).isEqualTo("2026-05-28T19:55:42Z");
        assertThat(posts.getFirst().get("title")).isEqualTo("Texto real da postagem");
        assertThat(posts.getFirst().get("url")).isEqualTo("https://www.youtube.com/post/post-1");
    }

    @Test
    void importsAdditionalTemporalFormatsFromCurrentTakeout() throws Exception {
        Path takeout = tempDir.resolve("Takeout");
        write(
                takeout.resolve("Agenda/principal.ics"),
                """
        BEGIN:VCALENDAR
        BEGIN:VEVENT
        DTSTART:20260704T120000Z
        SUMMARY:Evento real
        DESCRIPTION:Descrição
        END:VEVENT
        END:VCALENDAR
        """);
        write(
                takeout.resolve("Atividade do Registro de acesso/Atividades_ conta.csv"),
                """
        Activity Timestamp,Product Name,Sub-Product Name,Activity Type,Activity Country,Activity Region,Activity City,User Agent String,Gmail Access Channel
        2026-07-09 06:56:39 UTC,Gmail,,Login,BR,SP,Guarulhos,Android,Mobile
        """);
        write(
                takeout.resolve("Google Meet/ConferenceHistory/conference_history_records.csv"),
                """
        Meeting Code,Start Time,Call Direction,Duration,Direct Call Result,Participation State,Call Counterparts
        abc-defg-hij,2025-09-01 19:24:36 UTC,Outgoing,0:02:16,Answered,Joined,User
        """);
        write(
                takeout.resolve("Google Pay/Transações no Google Pay/transactions.csv"),
                """
        Hora,Código da transação,Descrição,Produto,Forma de pagamento,Status,Valor
        "4 de jul. de 2026, 18:23",tx-1,Assinatura,Google Play,Cartão,Concluir,"BRL 4,99"
        """);
        write(
                takeout.resolve("Google Play Store/Installs.json"),
                """
        [{"install":{"doc":{"title":"Aplicativo"},"firstInstallationTime":"2023-11-13T06:06:46Z"}}]
        """);
        write(
                takeout.resolve("Tarefas/Tasks.json"),
                """
        {"items":[{"kind":"tasks#task","created":"2022-07-07T04:39:11Z","title":"Tarefa real"}]}
        """);
        write(
                takeout.resolve("NotebookLM/Projeto/Projeto metadata.json"),
                """
        {"title":"Projeto de pesquisa","metadata":{"createTime":"2026-05-31T00:17:25Z"}}
        """);
        write(
                takeout.resolve("Blogger/Comments/blog/feed.atom"),
                """
        <feed xmlns:blogger="http://schemas.google.com/blogger/2018"><entry>
          <content type="html">Comentário real</content><blogger:created>2020-12-12T13:53:15Z</blogger:created>
        </entry></feed>
        """);
        write(
                takeout.resolve("YouTube e YouTube Music/mensagens/conversation.csv"),
                """
        ID da mensagem,ID do canal do remetente,Carimbo de data/hora da criação da mensagem (UTC),ID da conversa,IDs dos canais dos destinatários:,Mídia compartilhada: ID do vídeo
        msg-1,sender,2026-06-23T23:52:16Z,conversation,recipient,video-1
        """);
        write(
                takeout.resolve("YouTube e YouTube Music/metadados do vídeo/vídeos.csv"),
                """
        ID do vídeo,Título do vídeo (original),Descrição do vídeo (original),Categoria do vídeo,Privacidade,Estado do vídeo,Carimbo de data/hora de criação do vídeo,Carimbo de data/hora de publicação do vídeo
        video-1,Vídeo real,Descrição,Pessoas,Público,Ativo,2024-09-02T21:10:59Z,2024-09-02T22:00:00Z
        """);
        write(
                takeout.resolve("E-mail/mail.mbox"),
                """
        From message@example.com Wed Jul 08 23:53:00 +0000 2026
        From: message@example.com
        To: receiver@example.com
        Subject: =?UTF-8?Q?Assunto_sem_data?=
        Content-Type: text/plain; charset=UTF-8

        Corpo.
        """);

        DatabaseService database = database();
        TakeoutImporter importer =
                new TakeoutImporter(database, new ObjectMapper(), takeout.toString(), tempDir.toString());
        ImportResult result = importer.importTakeout(takeout.toString());

        assertThat(result.eventCount()).isEqualTo(11);
        assertThat(database.events(
                        FilterParams.of(
                                "", "", "calendar,meeting,purchase,install,task,notebook,message,upload", "", "", ""),
                        20,
                        0))
                .hasSize(8);
        assertThat(database.events(FilterParams.of("", "Registro de acesso", "access", "", "", ""), 20, 0))
                .hasSize(1);
        assertThat(database.events(FilterParams.of("", "Blogger", "comment", "", "", ""), 20, 0))
                .hasSize(1);
        assertThat(database.events(FilterParams.of("", "Gmail", "email", "", "", ""), 20, 0))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.get("timestamp")).isEqualTo("2026-07-08T23:53:00Z");
                    assertThat(row.get("title")).isEqualTo("Assunto sem data");
                });
        assertThat(database.events(FilterParams.of("missing:time", "", "", "", "", ""), 20, 0))
                .isEmpty();
    }

    private DatabaseService database() throws Exception {
        Path db = tempDir.resolve("db");
        Files.createDirectories(db);
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + db.resolve("memoria.db"));
        this.jdbc = new JdbcTemplate(dataSource);
        DatabaseService database = new DatabaseService(
                db.toString(),
                jdbc,
                new NamedParameterJdbcTemplate(jdbc),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        database.init();
        return database;
    }

    private void writeRepresentativeTakeout(Path takeout) throws Exception {
        write(
                takeout.resolve("Minha atividade/Search/Minhaatividade.html"),
                """
        <html><body>
          <div class="outer-cell">
            <a href="https://www.google.com/search?q=google+baixar+informa%C3%A7%C3%B5es">google baixar informações</a>
            18 de mar. de 2025, 23:46:47 BRT
            <div>Produtos: Search</div>
            <div>Por que isso está aqui?</div>
          </div>
          <div class="content-cell">Produtos: Search</div>
        </body></html>
        """);
        write(
                takeout.resolve("YouTube e YouTube Music/histórico/histórico-de-visualização.html"),
                """
        <html><body>
          <div class="outer-cell">
            YouTube Watched Vídeo de teste
            <a href="https://www.youtube.com/watch?v=abc123">Vídeo de teste</a>
            18 de mar. de 2025, 23:42:43 BRT
          </div>
        </body></html>
        """);
        write(
                takeout.resolve("YouTube e YouTube Music/comentários/comentários.csv"),
                """
        ID do comentário,ID do canal,Carimbo de data/hora em que o comentário foi criado,Preço,ID do comentário principal,ID da postagem,ID do vídeo,Texto do comentário,ID do comentário de nível superior
        c1,channel,2025-03-19T01:55:20.703272+00:00,0,,,vid1,"{""text"":""comentário de teste""}",
        """);
        write(
                takeout.resolve("YouTube e YouTube Music/chats ao vivo/chats ao vivo.csv"),
                """
        ID do chat ao vivo,ID do canal,Marcação de tempo da criação do chat ao vivo,Preço,Primeiro ID do chat ao vivo,ID do vídeo,Texto do chat ao vivo
        chat1,channel,2025-03-02T11:51:05.48138+00:00,0,,live1,"{""text"":""chat de teste""}"
        """);
        write(
                takeout.resolve("Chrome/Histórico.json"),
                """
        {"Browser History":[{"title":"Página de teste","url":"https://example.com/a","time_usec":1742334074407350,"page_transition":"LINK"}]}
        """);
        write(
                takeout.resolve("E-mail/Todos os e-mails, incluindo Spam e Lixeira.mbox"),
                """
        From sender@example.com Tue Mar 18 23:00:00 2025
        From: sender@example.com
        To: receiver@example.com
        Subject: Assunto de teste
        Date: Tue, 18 Mar 2025 23:00:00 +0000
        Content-Type: text/plain; charset=UTF-8

        Corpo do email.
        """);
        write(
                takeout.resolve("Maps/Perguntas e respostas/Perguntas e respostas.json"),
                """
        {"questions":[{"creationTime":"2025-03-18T10:15:30Z","text":"Pergunta de teste","place_url":"https://maps.google.com/maps/?cid=1"}]}
        """);
    }

    private void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
