import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PranchaApp extends Application {

    private static final Path ARQUIVO_PRANCHA =
            Path.of("prancha-gerada.json");

    private static final Path PASTA_IMAGENS =
            Path.of("imagens");

    private static final ObjectMapper MAPPER =
            new ObjectMapper();

    private final TextField campoFrase =
            new TextField();

    @Override
    public void start(Stage stage) throws Exception {

        List<ItemPalavra> todos =
                lerPrancha(ARQUIVO_PRANCHA);

        List<ItemPalavra> itens =
                todos.stream()
                        .filter(PranchaApp::temImagem)
                        .toList();

        System.out.println(
                "Palavras na prancha: " + todos.size()
        );

        for (ItemPalavra item : todos) {

            boolean tem =
                    temImagem(item);

            System.out.println(
                    "  - "
                            + item.palavra()
                            + " ("
                            + item.tipo()
                            + "): "
                            + (tem ? "imagem encontrada" : "SEM imagem, ignorada")
                            + " -> procurando "
                            + PASTA_IMAGENS.resolve(normalizarNomeArquivo(item.palavra()) + ".png").toAbsolutePath()
            );
        }

        FlowPane grade =
                new FlowPane(16, 16);

        grade.setPadding(new Insets(20));

        for (ItemPalavra item : itens) {

            grade.getChildren().add(
                    montarCard(item)
            );
        }

        if (itens.isEmpty()) {

            Label vazio =
                    new Label(
                            todos.isEmpty()
                                    ? "Nenhuma palavra encontrada em "
                                    + ARQUIVO_PRANCHA.toAbsolutePath()
                                    : "Nenhuma palavra da prancha tem imagem em "
                                    + PASTA_IMAGENS.toAbsolutePath()
                    );

            grade.getChildren().add(vazio);
        }

        ScrollPane scroll =
                new ScrollPane(grade);

        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("scroll-grade");

        campoFrase.setPromptText(
                "Digite ou clique nos pictogramas..."
        );

        campoFrase.getStyleClass().add("campo-frase");

        HBox.setHgrow(campoFrase, javafx.scene.layout.Priority.ALWAYS);

        Button botaoFalar =
                new Button("🔊 Falar");

        botaoFalar.getStyleClass().add("botao-falar");

        botaoFalar.setOnAction(
                e -> falar(campoFrase.getText())
        );

        Button botaoApagar =
                new Button("⌫");

        botaoApagar.getStyleClass().add("botao-secundario");

        botaoApagar.setOnAction(e -> apagarUltimaPalavra());

        Button botaoLimpar =
                new Button("🗑");

        botaoLimpar.getStyleClass().add("botao-secundario");

        botaoLimpar.setOnAction(e -> campoFrase.clear());

        HBox barraSuperior =
                new HBox(
                        10,
                        campoFrase,
                        botaoFalar,
                        botaoApagar,
                        botaoLimpar
                );

        barraSuperior.setAlignment(Pos.CENTER);
        barraSuperior.setPadding(new Insets(14));
        barraSuperior.getStyleClass().add("barra-superior");

        BorderPane raiz =
                new BorderPane();

        raiz.setTop(barraSuperior);
        raiz.setCenter(scroll);

        Scene cena =
                new Scene(raiz, 960, 680);

        cena.getStylesheets().add(
                getClass().getResource("/javafx/style.css").toExternalForm()
        );

        stage.setScene(cena);
        stage.setTitle("Minha Prancha");
        stage.show();

        campoFrase.requestFocus();
    }

    private static String normalizarNomeArquivo(String palavra) {

        String semAcento =
                java.text.Normalizer.normalize(
                        palavra,
                        java.text.Normalizer.Form.NFD
                ).replaceAll("\\p{M}", "");

        return semAcento;
    }

    private static boolean temImagem(ItemPalavra item) {

        return Files.exists(
                PASTA_IMAGENS.resolve(
                        normalizarNomeArquivo(item.palavra()) + ".png"
                )
        );
    }

    private VBox montarCard(ItemPalavra item) {

        VBox card =
                new VBox(6);

        card.getStyleClass().addAll(
                "picto",
                "tipo-" + item.tipo()
        );

        card.setAlignment(Pos.CENTER);
        card.setPrefWidth(150);
        card.setPadding(new Insets(10));

        Path caminhoImagem =
                PASTA_IMAGENS.resolve(
                        normalizarNomeArquivo(item.palavra()) + ".png"
                );

        ImageView imageView =
                new ImageView(
                        new Image(
                                caminhoImagem.toUri().toString(),
                                120,
                                120,
                                true,
                                true
                        )
                );

        card.getChildren().add(imageView);

        Label rotulo =
                new Label(item.palavra());

        rotulo.getStyleClass().add("rotulo");

        card.getChildren().add(rotulo);

        card.setOnMouseClicked(
                e -> adicionarPalavra(item)
        );

        return card;
    }

    /**
     * Insere a palavra na posição atual do cursor do campo de texto
     * (em vez de sempre no final), então clicar e digitar podem se
     * intercalar naturalmente. Também fala a palavra em voz alta.
     */
    private void adicionarPalavra(ItemPalavra item) {

        int posicao =
                campoFrase.getCaretPosition();

        String textoAtual =
                campoFrase.getText();

        boolean precisaEspacoAntes =
                posicao > 0
                        && !Character.isWhitespace(
                        textoAtual.charAt(posicao - 1)
                );

        String textoInserir =
                (precisaEspacoAntes ? " " : "")
                        + item.palavra()
                        + " ";

        campoFrase.insertText(posicao, textoInserir);

        falar(item.palavra());
    }

    private void apagarUltimaPalavra() {

        String texto =
                campoFrase.getText().stripTrailing();

        if (texto.isBlank()) {

            campoFrase.clear();

            return;
        }

        int ultimoEspaco =
                texto.lastIndexOf(' ');

        String restante =
                ultimoEspaco >= 0
                        ? texto.substring(0, ultimoEspaco + 1)
                        : "";

        campoFrase.setText(restante);

        campoFrase.positionCaret(restante.length());
    }

    /**
     * Usa o comando "say" do macOS para falar o texto em voz alta.
     * A voz "Luciana" é a voz em português (Brasil) do sistema; se ela
     * não estiver instalada (Ajustes do Sistema > Acessibilidade >
     * Conteúdo Falado > Vozes do Sistema), o comando falha e a
     * exceção é só logada, sem travar a aplicação.
     */
    private void falar(String texto) {

        if (texto == null || texto.isBlank()) {
            return;
        }

        try {

            new ProcessBuilder("say", "-v", "Luciana", texto)
                    .start();

        } catch (Exception e) {

            System.out.println(
                    "Não foi possível falar (comando 'say' é só macOS, "
                            + "ou a voz 'Luciana' não está instalada): "
                            + e.getMessage()
            );
        }
    }

    private static List<ItemPalavra> lerPrancha(
            Path arquivo
    ) throws Exception {

        List<ItemPalavra> resultado =
                new ArrayList<>();

        if (!Files.exists(arquivo)) {

            System.out.println(
                    "Arquivo não encontrado: "
                            + arquivo.toAbsolutePath()
            );

            return resultado;
        }

        String conteudo =
                Files.readString(arquivo);

        JsonNode raiz =
                MAPPER.readTree(conteudo);

        JsonNode itensNode =
                raiz.get("itens");

        if (itensNode == null || !itensNode.isArray()) {

            System.out.println(
                    "Campo 'itens' não encontrado ou não é um array."
            );

            return resultado;
        }

        for (JsonNode item : itensNode) {

            String palavra =
                    item.path("palavra")
                            .asText("")
                            .strip()
                            .toLowerCase(Locale.ROOT);

            String tipo =
                    item.path("tipo")
                            .asText("")
                            .strip()
                            .toLowerCase(Locale.ROOT);

            if (palavra.isBlank() || tipo.isBlank()) {
                continue;
            }

            resultado.add(
                    new ItemPalavra(palavra, tipo)
            );
        }

        return resultado;
    }

    public static void main(String[] args) {
        launch(args);
    }

    private record ItemPalavra(
            String palavra,
            String tipo
    ) {
    }
}