package app.memoria;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class YouTubeChannelsTest {
    @Test
    void extractsChannelBetweenTitleAndPtBrDate() {
        String html = "YouTube Watched o começo MALUCO da segunda temporada de LOKI Hamlet Segundo "
                + "18 de mar. de 2025, 21:52:54 BRT Produtos:  YouTube";
        assertThat(YouTubeChannels.extract("o começo MALUCO da segunda temporada de LOKI", html))
                .isEqualTo("Hamlet Segundo");
    }

    @Test
    void extractsChannelFromLikedEntry() {
        String html =
                "YouTube Liked Back To The Kindergarten (Análise) Hora Cartoon 4 de dez. de 2017, 17:50:32 BRT Produtos:  YouTube";
        assertThat(YouTubeChannels.extract("Back To The Kindergarten (Análise)", html))
                .isEqualTo("Hora Cartoon");
    }

    @Test
    void adsEntriesWithoutChannelYieldNull() {
        String html = "YouTube Watched Albion Online Teaser Watched at 11:38 12 de fev. de 2022, 11:38:09 BRT "
                + "Produtos:  YouTube Detalhes:  From Google Ads";
        assertThat(YouTubeChannels.extract("Albion Online Teaser", html)).isNull();
    }

    @Test
    void subscriptionUsesTitleAsChannel() {
        String html = "YouTube Subscribed to Peridot TV 7 de dez. de 2017, 15:46:55 BRT Produtos:  YouTube";
        assertThat(YouTubeChannels.extract("Peridot TV", html)).isEqualTo("Peridot TV");
    }

    @Test
    void entryWithoutChannelBeforeDateYieldsNull() {
        String html = "YouTube Watched Vídeo sem canal 7 de dez. de 2017, 13:25:37 BRT Produtos:  YouTube";
        assertThat(YouTubeChannels.extract("Vídeo sem canal", html)).isNull();
    }

    @Test
    void missingTitleOrDateYieldsNull() {
        assertThat(YouTubeChannels.extract("Título", "texto sem o título nem data"))
                .isNull();
        assertThat(YouTubeChannels.extract("Título", "YouTube Watched Título Canal sem data alguma"))
                .isNull();
        assertThat(YouTubeChannels.extract(null, "qualquer coisa")).isNull();
        assertThat(YouTubeChannels.extract("Título", null)).isNull();
    }

    @Test
    void isoTimestampAlsoDelimitsChannel() {
        String html = "YouTube Watched Algum vídeo Canal Exemplo 2024-05-10T12:00:00Z";
        assertThat(YouTubeChannels.extract("Algum vídeo", html)).isEqualTo("Canal Exemplo");
    }

    @Test
    void overlongCandidatesAreRejected() {
        String junk = "x".repeat(140);
        String html = "YouTube Watched Vídeo " + junk + " 18 de mar. de 2025, 21:52:54 BRT";
        assertThat(YouTubeChannels.extract("Vídeo", html)).isNull();
    }
}
