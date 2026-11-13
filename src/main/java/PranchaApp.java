import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.concurrent.Task;
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

/**
 * Interface de comunicação e de criação de pranchas.
 *
 * A tela lê o contrato simples de arquivos do projeto: prancha-gerada.json
 * contém as palavras e imagens/ contém os PNGs. Esse desacoplamento mantém
 * a prancha disponível offline e deixa a geração substituível, mas o diretório
 * de execução precisa ser o diretório do projeto para os caminhos relativos baterem.
 */
public class PranchaApp extends Application {

    // Arquivos relativos mantêm o formato de integração entre a geração e a interface.
    // Trade-off: iniciar o app a partir de outra pasta altera onde esses caminhos apontam.
    private static final Path ARQUIVO_PRANCHA =
            Path.of("prancha-gerada.json");

    private static final Path PASTA_IMAGENS =
            Path.of("imagens");

    private static final ObjectMapper MAPPER =
            new ObjectMapper();

    // O texto permanece editável para combinar digitação com seleção por imagem.
    private final TextField campoFrase =
            new TextField();

    // TextField pode perder o foco ao tocar num cartão; guardamos o último cursor
    // enquanto ele está focado para que a palavra entre no ponto de escrita correto.
    private int ultimaPosicaoCursor;

    private final TextField campoTema =
            new TextField();

    private final FlowPane grade =
            new FlowPane(16, 16);

    private final Label statusGeracao =
            new Label("Informe um tema para criar uma prancha nova.");

    private final ProgressIndicator indicadorGeracao =
            new ProgressIndicator();

    private final Button botaoGerar =
            new Button("Criar prancha e gerar fotos");

    private final Button botaoFotosAtuais =
            new Button("Gerar fotos da prancha atual");

    /** Monta as duas áreas numa janela única e inicia mostrando a prancha atual. */
    @Override
    public void start(Stage stage) throws Exception {

        TabPane abas = new TabPane();

        Tab abaPrancha = new Tab("Minha prancha", montarTelaPrancha());
        abaPrancha.setClosable(false);

        Tab abaCriar = new Tab("Criar prancha", montarTelaCriacao());
        abaCriar.setClosable(false);

        abas.getTabs().addAll(abaPrancha, abaCriar);

        Scene cena = new Scene(abas, 1000, 720);

        cena.getStylesheets().add(
                getClass().getResource("/javafx/style.css").toExternalForm()
        );

        stage.setScene(cena);
        stage.setTitle("Prancha de Comunicação");
        stage.show();

        campoFrase.requestFocus();
    }

    /**
     * Monta a área usada para formar frases e falar palavras.
     * Só cria cartões para arquivos existentes: isso evita imagens quebradas,
     * embora uma palavra sem PNG fique temporariamente oculta até a geração terminar.
     */
    private BorderPane montarTelaPrancha() throws Exception {

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

            boolean tem = temImagem(item);

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

        grade.setPadding(new Insets(20));
        preencherGrade(todos, itens);

        ScrollPane scroll = new ScrollPane(grade);

        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("scroll-grade");

        campoFrase.setPromptText("Digite ou toque nas fotos para montar uma frase...");

        campoFrase.getStyleClass().add("campo-frase");

        // Atualiza a posição salva somente enquanto o campo tem foco. Quando o
        // foco sai para o cartão, a posição visível do controle pode não ser confiável.
        campoFrase.caretPositionProperty().addListener(
                (observable, anterior, atual) -> {
                    if (campoFrase.isFocused()) {
                        ultimaPosicaoCursor = atual.intValue();
                    }
                }
        );

        HBox.setHgrow(campoFrase, javafx.scene.layout.Priority.ALWAYS);

        Button botaoFalar = new Button("🔊 Falar");

        botaoFalar.getStyleClass().add("botao-falar");

        botaoFalar.setOnAction(e -> falar(campoFrase.getText()));

        Button botaoApagar = new Button("⌫");

        botaoApagar.getStyleClass().add("botao-secundario");

        botaoApagar.setOnAction(e -> apagarUltimaPalavra());

        Button botaoLimpar = new Button("🗑");

        botaoLimpar.getStyleClass().add("botao-secundario");

        botaoLimpar.setOnAction(e -> {
            campoFrase.clear();
            ultimaPosicaoCursor = 0;
        });

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

        BorderPane raiz = new BorderPane();

        raiz.setTop(barraSuperior);
        raiz.setCenter(scroll);

        return raiz;
    }

    /**
     * Reúne a entrada de tema, criação de vocabulário e geração de fotos numa aba.
     * Um fluxo só reduz a quantidade de telas e cliques; em compensação, o modelo
     * local ainda gera uma quantidade fixa de palavras configurada em acc.java.
     */
    private VBox montarTelaCriacao() {

        Label titulo = new Label("Crie uma prancha nova");
        titulo.getStyleClass().add("titulo-geracao");

        Label descricao = new Label(
                "Descreva o tema. O sistema monta o vocabulário e gera fotos realistas para cada palavra."
        );
        descricao.getStyleClass().add("descricao-geracao");
        descricao.setWrapText(true);

        campoTema.setPromptText("Ex.: rotina da manhã, praia, escola");
        campoTema.setText(lerPedidoAtual());
        campoTema.getStyleClass().add("campo-frase");
        campoTema.setOnAction(event -> iniciarGeracao());

        botaoGerar.getStyleClass().add("botao-gerar");
        botaoGerar.setOnAction(event -> iniciarGeracao());

        botaoFotosAtuais.getStyleClass().add("botao-secundario");
        botaoFotosAtuais.setOnAction(event -> gerarFotosDaPranchaAtual());

        indicadorGeracao.setMaxSize(28, 28);
        indicadorGeracao.setVisible(false);
        indicadorGeracao.setManaged(false);

        statusGeracao.getStyleClass().add("status-geracao");
        statusGeracao.setWrapText(true);

        HBox entrada = new HBox(12, campoTema, botaoGerar);
        entrada.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(campoTema, javafx.scene.layout.Priority.ALWAYS);

        HBox andamento = new HBox(10, indicadorGeracao, statusGeracao);
        andamento.setAlignment(Pos.CENTER_LEFT);

        VBox painel = new VBox(
                18,
                titulo,
                descricao,
                entrada,
                botaoFotosAtuais,
                andamento
        );
        painel.getStyleClass().add("painel-geracao");
        painel.setMaxWidth(760);

        VBox tela = new VBox(painel);
        tela.setAlignment(Pos.TOP_CENTER);
        tela.setPadding(new Insets(48, 28, 28, 28));
        tela.getStyleClass().add("tela-geracao");

        return tela;
    }

    /**
     * Agenda a criação completa fora da thread visual: Ollama e difusão podem levar
     * minutos, então executá-los na thread JavaFX congelaria botões e animações.
     */
    private void iniciarGeracao() {

        String tema = campoTema.getText() == null
                ? ""
                : campoTema.getText().strip();

        if (tema.isBlank()) {
            statusGeracao.setText("Digite um tema para começar.");
            campoTema.requestFocus();
            return;
        }

        Task<Void> tarefa = new Task<>() {
            @Override
            protected Void call() throws Exception {
                List<acc.CandidatoIA> palavras =
                        acc.gerarVocabulario(tema, this::updateMessage);
                acc.gerarImagens(palavras, tema, this::updateMessage);
                return null;
            }
        };

        executarGeracao(
                tarefa,
                "Prancha pronta. As fotos já estão disponíveis na aba Minha prancha."
        );
    }

    /**
     * Refaz as imagens para as palavras que já estão no JSON sem pedir vocabulário novo.
     * É útil ao ajustar os prompts; cada arquivo correspondente é substituído quando a
     * geração termina. O custo é que uma nova rodada refaz todas as fotos da prancha.
     */
    private void gerarFotosDaPranchaAtual() {

        try {
            List<acc.CandidatoIA> palavras = lerPrancha(ARQUIVO_PRANCHA)
                    .stream()
                    .map(item -> new acc.CandidatoIA(item.palavra(), item.tipo()))
                    .toList();

            if (palavras.isEmpty()) {
                statusGeracao.setText("Ainda não há uma prancha para gerar fotos. Informe um tema acima.");
                return;
            }

            Task<Void> tarefa = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    acc.gerarImagens(
                            palavras,
                            lerPedidoAtual(),
                            this::updateMessage
                    );
                    return null;
                }
            };

            executarGeracao(
                    tarefa,
                    "Fotos atualizadas. Veja o resultado na aba Minha prancha."
            );
        } catch (Exception e) {
            statusGeracao.setText("Não foi possível ler a prancha atual: " + e.getMessage());
        }
    }

    /**
     * Centraliza o estado visual das tarefas demoradas, o progresso e os erros.
     * O JavaFX Task publica mensagens com segurança entre a thread de geração e a UI.
     * Em falha parcial, atualizamos a grade para mostrar PNGs que já foram concluídos.
     */
    private void executarGeracao(Task<Void> tarefa, String mensagemSucesso) {

        botaoGerar.setDisable(true);
        botaoFotosAtuais.setDisable(true);
        campoTema.setDisable(true);
        indicadorGeracao.setVisible(true);
        indicadorGeracao.setManaged(true);

        statusGeracao.textProperty().bind(tarefa.messageProperty());

        tarefa.setOnSucceeded(event -> {
            statusGeracao.textProperty().unbind();
            statusGeracao.setText(mensagemSucesso);
            concluirGeracao();
            atualizarGrade();
        });

        tarefa.setOnFailed(event -> {
            statusGeracao.textProperty().unbind();
            Throwable erro = tarefa.getException();
            String mensagem = erro == null || erro.getMessage() == null
                    ? "Não foi possível concluir a geração. Confira o Ollama e o Stable Diffusion."
                    : "A geração parou: " + erro.getMessage();
            statusGeracao.setText(mensagem);
            concluirGeracao();
            atualizarGrade();
        });

        Thread thread = new Thread(tarefa, "geracao-prancha");
        thread.setDaemon(true);
        thread.start();
    }

    /** Reativa os controles tanto após sucesso quanto após falha. */
    private void concluirGeracao() {
        botaoGerar.setDisable(false);
        botaoFotosAtuais.setDisable(false);
        campoTema.setDisable(false);
        indicadorGeracao.setVisible(false);
        indicadorGeracao.setManaged(false);
    }

    /** Reabre o JSON depois da geração para refletir arquivos novos ou substituídos. */
    private void atualizarGrade() {
        try {
            List<ItemPalavra> todos = lerPrancha(ARQUIVO_PRANCHA);
            List<ItemPalavra> itens = todos.stream()
                    .filter(PranchaApp::temImagem)
                    .toList();
            preencherGrade(todos, itens);
        } catch (Exception e) {
            grade.getChildren().setAll(
                    new Label("Não foi possível atualizar a prancha: " + e.getMessage())
            );
        }
    }

    /**
     * Recupera o tema salvo para contextualizar uma nova geração da prancha existente.
     * Se o JSON não puder ser lido, usa tema vazio; isso mantém a tela aberta, mas deixa
     * a tradução de palavras ambíguas sem essa pista contextual.
     */
    private String lerPedidoAtual() {
        if (!Files.exists(ARQUIVO_PRANCHA)) {
            return "";
        }

        try {
            JsonNode raiz = MAPPER.readTree(Files.readString(ARQUIVO_PRANCHA));
            return raiz.path("pedidoOriginal").asText("").strip();
        } catch (Exception e) {
            return "";
        }
    }

    /** Reconstrói os cartões a partir dos dados e dos PNGs que existem neste momento. */
    private void preencherGrade(
            List<ItemPalavra> todos,
            List<ItemPalavra> itens
    ) {
        grade.getChildren().clear();

        for (ItemPalavra item : itens) {
            grade.getChildren().add(montarCard(item));
        }

        if (itens.isEmpty()) {
            Label vazio = new Label(
                    todos.isEmpty()
                            ? "Nenhuma palavra encontrada em " + ARQUIVO_PRANCHA.toAbsolutePath()
                            : "Ainda não há fotos geradas para esta prancha. Use a aba Criar prancha."
            );
            vazio.setWrapText(true);
            grade.getChildren().add(vazio);
        }
    }

    /** Remove acentos para que o nome do PNG corresponda à palavra do JSON. */
    private static String normalizarNomeArquivo(String palavra) {

        String semAcento = java.text.Normalizer.normalize(
                palavra,
                java.text.Normalizer.Form.NFD
                ).replaceAll("\\p{M}", "");

        return semAcento;
    }

    /** A presença do arquivo é o critério simples para exibir a palavra na grade. */
    private static boolean temImagem(ItemPalavra item) {
        return Files.exists(
                PASTA_IMAGENS.resolve(
                        normalizarNomeArquivo(item.palavra()) + ".png"
                )
        );
    }

    /** Cria um cartão visual e associa o clique à inserção e à fala da palavra. */
    private VBox montarCard(ItemPalavra item) {

        VBox card = new VBox(6);

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

        Label rotulo = new Label(item.palavra());

        rotulo.getStyleClass().add("rotulo");

        card.getChildren().add(rotulo);

        card.setOnMouseClicked(e -> adicionarPalavra(item));

        return card;
    }

    /**
     * Insere a palavra na última posição válida do cursor, inclusive quando o
     * campo perdeu o foco ao tocar no cartão. Depois move o cursor para o fim da
     * palavra inserida, para que a digitação possa continuar dali.
     *
     * O espaçamento automático torna cliques e texto natural de combinar, mas não
     * tenta corrigir pontuação ou gramática. A palavra também é falada imediatamente.
     */
    private void adicionarPalavra(ItemPalavra item) {

        String textoAtual = campoFrase.getText();
        int posicao = Math.max(
                0,
                Math.min(ultimaPosicaoCursor, textoAtual.length())
        );

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

        int novaPosicao = posicao + textoInserir.length();
        campoFrase.requestFocus();
        campoFrase.positionCaret(novaPosicao);
        ultimaPosicaoCursor = novaPosicao;

        falar(item.palavra());
    }

    /** Remove o último trecho separado por espaço; é simples e funciona com o vocabulário atual. */
    private void apagarUltimaPalavra() {

        String texto = campoFrase.getText().stripTrailing();

        if (texto.isBlank()) {
            campoFrase.clear();
            ultimaPosicaoCursor = 0;

            return;
        }

        int ultimoEspaco = texto.lastIndexOf(' ');

        String restante =
                ultimoEspaco >= 0
                        ? texto.substring(0, ultimoEspaco + 1)
                        : "";

        campoFrase.setText(restante);

        campoFrase.positionCaret(restante.length());
        ultimaPosicaoCursor = restante.length();
    }

    /**
     * Usa o comando "say" do macOS para falar o texto em voz alta.
     * A voz "Luciana" é a voz em português (Brasil) do sistema; se ela
     * não estiver instalada (Ajustes do Sistema > Acessibilidade >
     * Conteúdo Falado > Vozes do Sistema), o comando falha e a
     * exceção é só logada, sem travar a aplicação.
     *
     * A síntese local preserva privacidade e uso sem rede; o trade-off é a
     * dependência do comando e da voz Luciana, que não são portáveis para todos os SOs.
     */
    private void falar(String texto) {

        if (texto == null || texto.isBlank()) {
            return;
        }

        try {
            new ProcessBuilder("say", "-v", "Luciana", texto).start();

        } catch (Exception e) {

            System.out.println(
                    "Não foi possível falar (comando 'say' é só macOS, "
                            + "ou a voz 'Luciana' não está instalada): "
                            + e.getMessage()
            );
        }
    }

    /**
     * Lê e valida o subconjunto do JSON necessário pela interface.
     * Itens vazios ou sem tipo são ignorados, mantendo a prancha utilizável diante
     * de dados parciais; o restante do esquema é controlado pela etapa de geração.
     */
    private static List<ItemPalavra> lerPrancha(
            Path arquivo
    ) throws Exception {

        List<ItemPalavra> resultado = new ArrayList<>();

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

    /** Ponto de entrada JavaFX da aplicação desktop. */
    public static void main(String[] args) {
        launch(args);
    }

    /** Modelo local da interface; mantém o vocabulário separado da geração de texto. */
    private record ItemPalavra(
            String palavra,
            String tipo
    ) {
    }
}
