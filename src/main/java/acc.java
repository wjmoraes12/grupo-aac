import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Pipeline local que transforma um tema em vocabulário, JSON e fotografias.
 *
 * O Ollama e o Stable Diffusion são executados na máquina do usuário, e o JSON
 * junto com a pasta imagens/ conecta as etapas. Isso evita enviar dados à nuvem
 * e permite abrir uma prancha pronta offline; o custo é instalar e manter dois
 * modelos locais, além do tempo e da memória exigidos pela difusão.
 */
public class acc {

    // Ollama escuta apenas na máquina local; a aplicação não envia temas a um serviço externo.
    private static final String OLLAMA_HOST =
            "http://localhost:11434";

    // Modelo pequeno em português reduz memória e acelera a geração de vocabulário.
    // A contrapartida é que traduções e classificações podem exigir validação extra.
    private static final String MODELO =
            "hf.co/tardellirs/aac-board-generator-770m-ptbr-GGUF:Q6_K";

    // Um único ponto de configuração mantém a quantidade usada no prompt, no schema
    // e na validação final sincronizada. Hoje a prancha pede três palavras.
    private static final int QUANTIDADE_PALAVRAS = 3;

    // Gerar candidatos a mais dá opções para a seleção sem pedir vocabulário ilimitado.
    // Mais candidatos aumentam o custo da chamada ao Ollama e o tamanho do prompt.
    private static final int QUANTIDADE_CANDIDATOS = 25;

    // Repetições recuperam respostas JSON malformadas sem manter um loop indefinido.
    private static final int MAX_TENTATIVAS_JSON = 3;

    // A interface usa as cores Fitzgerald: verbo e substantivo têm tratamentos distintos.
    private static final Set<String> TIPOS_PERMITIDOS =
            Set.of("v", "s");

    // Um cliente reutilizado mantém as conexões e define um limite para a conexão inicial.
    // As chamadas de geração têm timeout próprio, pois podem ser mais longas que a conexão.
    private static final HttpClient HTTP_CLIENT =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();

    // Compartilhar o mapper evita criar conversores diferentes para o mesmo contrato JSON.
    private static final ObjectMapper MAPPER =
            new ObjectMapper();

    /*
     * Pasta onde as imagens geradas pelo Stable Diffusion
     * serão armazenadas.
     */
    private static final Path DIRETORIO_IMAGENS =
            Path.of("imagens");


    /** Fluxo de terminal legado: entrada, seleção, persistência e geração das imagens. */
    public static void main(String[] args) throws Exception {

        Scanner scanner = new Scanner(System.in);

        System.out.print(
                "Descreva a prancha que você quer gerar: "
        );

        String pedido =
                scanner.nextLine().strip();

        if (pedido.isBlank()) {

            System.out.println(
                    "O pedido não pode estar vazio."
            );

            scanner.close();

            return;
        }


        /*
         * =====================================================
         * ETAPA 1
         * =====================================================
         * Produz uma lista ampla de candidatos. Separar geração e seleção ajuda
         * o modelo pequeno a explorar o tema antes de escolher poucas palavras,
         * mas exige duas chamadas de texto e acrescenta latência.
         */

        System.out.println();
        System.out.println(
                "Verificando conexão com o Ollama..."
        );

        verificarOllama();

        System.out.println(
                "Verificando se o modelo está instalado..."
        );

        verificarModelo();

        System.out.println();
        System.out.println(
                "Etapa 1/3 - Gerando candidatos..."
        );

        String respostaCandidatos =
                gerarCandidatos(pedido);


        System.out.println();
        System.out.println(
                "--- Candidatos gerados pela IA ---"
        );

        System.out.println(
                respostaCandidatos
        );


        /*
         * =====================================================
         * ETAPA 2
         * =====================================================
         * A segunda chamada seleciona e classifica. O resultado é validado abaixo;
         * a validação estrutural protege o contrato, mas não prova que a palavra
         * seja a melhor escolha semântica para toda pessoa usuária.
         */

        System.out.println();
        System.out.println(
                "Etapa 2/3 - IA revisando relevância semântica..."
        );

        List<CandidatoIA> palavrasSelecionadas =
                selecionarPalavras(
                        pedido,
                        respostaCandidatos
                );


        if (
                palavrasSelecionadas.size()
                        != QUANTIDADE_PALAVRAS
        ) {

            throw new IllegalStateException(
                    "A IA retornou "
                            + palavrasSelecionadas.size()
                            + " palavras válidas. "
                            + "Eram esperadas exatamente "
                            + QUANTIDADE_PALAVRAS
                            + "."
            );
        }


        System.out.println();
        System.out.println(
                "Palavras selecionadas:"
        );


        for (
                int i = 0;
                i < palavrasSelecionadas.size();
                i++
        ) {

            CandidatoIA item =
                    palavrasSelecionadas.get(i);

            System.out.printf(
                    "%02d. %s (%s)%n",
                    i + 1,
                    item.palavra(),
                    item.tipo()
            );
        }


        /*
         * =====================================================
         * CRIAÇÃO DA PRANCHA JSON
         * =====================================================
         * O arquivo funciona como contrato entre CLI, JavaFX e gerador de imagem.
         * Isso reduz o acoplamento entre etapas, mas mudanças no formato precisam
         * ser refletidas em todos os leitores do JSON.
         */

        Prancha prancha =
                new Prancha(
                        pedido,
                        palavrasSelecionadas
                );


        String json =
                MAPPER
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(prancha);


        Path arquivo =
                Path.of("prancha-gerada.json");


        Files.writeString(
                arquivo,
                json,
                StandardCharsets.UTF_8
        );


        System.out.println();
        System.out.println(
                "--- Prancha gerada ---"
        );

        System.out.println(json);


        System.out.println();
        System.out.println(
                "Arquivo salvo em:"
        );

        System.out.println(
                arquivo.toAbsolutePath()
        );


        /*
         * =====================================================
         * ETAPA 3
         * =====================================================
         * Cada foto é um processo local independente. O isolamento evita carregar
         * o modelo de difusão dentro da aplicação Java, mas torna a geração por
         * CPU lenta e repete a inicialização do modelo para cada palavra.
         */

        System.out.println();
        System.out.println(
                "Etapa 3/3 - Gerando imagens..."
        );


        gerarImagens(
                palavrasSelecionadas,
                pedido,
                System.out::println
        );


        System.out.println();
        System.out.println(
                "========================================"
        );

        System.out.println(
                "Prancha concluída com sucesso!"
        );

        System.out.println(
                "JSON: "
                        + arquivo.toAbsolutePath()
        );

        System.out.println(
                "Imagens: "
                        + DIRETORIO_IMAGENS.toAbsolutePath()
        );

        System.out.println(
                "========================================"
        );


        scanner.close();
    }


    /**
     * Gera o vocabulário localmente e salva o contrato consumido pelo JavaFX.
     *
     * A função também é chamada pela interface; o callback de progresso mantém
     * a tela informada sem obrigar este serviço a depender de JavaFX. O tema é
     * salvo antes das imagens para que uma falha de difusão não apague o vocabulário.
     * O trade-off é que o JSON pode existir mesmo quando uma ou mais fotos falham.
     */
    public static List<CandidatoIA> gerarVocabulario(
            String pedido,
            Consumer<String> progresso
    ) throws Exception {

        if (pedido == null || pedido.isBlank()) {
            throw new IllegalArgumentException(
                    "Descreva o tema da prancha antes de continuar."
            );
        }

        Consumer<String> atualizarProgresso =
                progresso == null ? mensagem -> { } : progresso;
        String tema = pedido.strip();

        atualizarProgresso.accept("Conectando ao Ollama local...");
        verificarOllama();

        atualizarProgresso.accept("Verificando o modelo de vocabulário...");
        verificarModelo();

        atualizarProgresso.accept("Criando vocabulário para a prancha...");
        String candidatos = gerarCandidatos(tema);
        List<CandidatoIA> selecionados =
                selecionarPalavras(tema, candidatos);

        if (selecionados.size() != QUANTIDADE_PALAVRAS) {
            throw new IllegalStateException(
                    "Não foi possível selecionar exatamente "
                            + QUANTIDADE_PALAVRAS
                            + " palavras válidas. Tente descrever o tema de outra forma."
            );
        }

        Prancha prancha = new Prancha(tema, selecionados);
        String json = MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(prancha);
        Path arquivo = Path.of("prancha-gerada.json");
        Files.writeString(arquivo, json, StandardCharsets.UTF_8);

        atualizarProgresso.accept(
                "Vocabulário pronto. Preparando "
                        + selecionados.size()
                        + " fotos..."
        );
        return selecionados;
    }

    /** Mantém compatibilidade com o fluxo de terminal e encaminha logs para stdout. */
    private static void gerarImagens(
            List<CandidatoIA> palavras
    ) throws Exception {

        gerarImagens(palavras, "", System.out::println);
    }

    /** Gera imagens sem tema contextual quando o chamador não possui esse dado. */
    public static void gerarImagens(
            List<CandidatoIA> palavras,
            Consumer<String> progresso
    ) throws Exception {

        gerarImagens(palavras, "", progresso);
    }

    /**
     * Gera uma foto por item e informa o andamento ao chamador.
     * A palavra em português continua sendo o nome do arquivo; o assunto da foto
     * é descrito em inglês porque o modelo SD 1.5 responde melhor a esse idioma.
     * O modelo é invocado por item para manter o contrato um PNG por palavra; o
     * custo é uma geração lenta para cada imagem e a possibilidade de falha parcial.
     */
    public static void gerarImagens(
            List<CandidatoIA> palavras,
            String tema,
            Consumer<String> progresso
    ) throws Exception {

        Consumer<String> atualizarProgresso =
                progresso == null ? mensagem -> { } : progresso;
        String contexto = tema == null ? "" : tema.strip();

        Files.createDirectories(
                DIRETORIO_IMAGENS
        );


        for (
                int i = 0;
                i < palavras.size();
                i++
        ) {

            CandidatoIA item =
                    palavras.get(i);


            String palavra =
                    item.palavra();


            String nomeArquivo =
                    normalizarNomeArquivo(
                            palavra
                    );


            Path destino =
                    DIRETORIO_IMAGENS.resolve(
                            nomeArquivo + ".png"
                    );


            System.out.println();

            String mensagem =
                    "Gerando foto "
                            + (i + 1)
                            + "/"
                            + palavras.size()
                            + ": "
                            + palavra;

            System.out.println(mensagem);
            atualizarProgresso.accept(mensagem);


            String palavraEmIngles =
                    traduzirParaIngles(
                            palavra,
                            item.tipo(),
                            contexto
                    );


            System.out.println(
                    "Traduzido para o prompt: "
                            + palavraEmIngles
            );


            ImagemGenerator.gerar(
                    palavraEmIngles,
                    destino
            );
        }
    }


    /*
     * =========================================================
     * PREPARA O ASSUNTO DA FOTO EM INGLÊS
     * =========================================================
     *
     * O Stable Diffusion 1.5 responde melhor a prompts em inglês.
     * Para verbos, a tradução descreve uma pessoa realizando a
     * ação, ajudando a produzir uma fotografia concreta.
     *
     * Termos comuns usam descrições revisadas no código. Para os demais, o Ollama
     * resolve a ambiguidade com o tema. A lista fixa é mais previsível, mas precisa
     * de manutenção; o fallback cobre vocabulário novo, porém ainda pode errar.
     */

    private static String traduzirParaIngles(
            String palavra,
            String tipo,
            String tema
    ) throws Exception {

        String descricaoConhecida =
                descricaoVisualConhecida(palavra, tema);

        if (descricaoConhecida != null) {
            return descricaoConhecida;
        }

        String prompt =
                """
                Create a short, exact English visual description for the Portuguese word "%s".

                Rules:
                - Grammatical type: %s (v means verb, s means noun).
                - Board theme for choosing the correct meaning: "%s".
                - Describe only the thing, person, or action named by the word, not a location or landscape.
                - For a verb, show one person clearly performing that action.
                - For a feeling or adjective, show a person clearly expressing it.
                - For a noun, name one common, recognizable real-world example.
                - Return only a concrete English phrase of two to six words.
                - Never invent a farm, field, house, or other background unless that is the word.
                - Do not explain or repeat the Portuguese word.
                """.formatted(
                        palavra,
                        tipo,
                        tema.isBlank() ? "not specified" : tema
                );

        String resposta =
                chamarOllama(
                        prompt,
                        48,
                        0.0,
                        null
                );

        String limpa =
                resposta
                        .strip()
                        .replaceAll(
                                "^[\"']+|[\"']+$",
                                ""
                        )
                        .split("\n")[0]
                        .replaceFirst("(?i)^(english|subject|translation)\\s*:\\s*", "")
                        .replaceFirst("^[\\-•*\\s]+", "")
                        .strip();

        if (limpa.isBlank()) {
            throw new IllegalStateException(
                    "Não foi possível identificar uma cena visual para \""
                            + palavra
                            + "\". Nenhuma imagem foi gerada com um assunto incorreto."
            );
        }

        return limpa;
    }

    /**
     * Mapeia palavras frequentes para assuntos visuais curtos e explícitos.
     * Isso contorna traduções genéricas do modelo pequeno nos conceitos de CAA mais
     * comuns. A contrapartida é cobertura limitada: uma palavra fora da lista usa
     * a tradução contextual do Ollama definida logo acima.
     */
    private static String descricaoVisualConhecida(
            String palavra,
            String tema
    ) {
        String chave = normalizarNomeArquivo(palavra);
        String contexto = normalizarNomeArquivo(tema);
        boolean temaEscolar = contexto.contains("escola");

        return switch (chave) {
            case "escola" -> "the front entrance of a real school building";
            case "brincar" -> temaEscolar
                    ? "a child playing with colorful blocks in a school playground"
                    : "a child playing with colorful toys";
            case "feliz", "alegre" -> "a happy child smiling with a joyful expression";
            case "triste" -> "a sad child with a tearful expression";
            case "bravo", "irritado" -> "an angry child showing a clear frown";
            case "medo", "assustado" -> "a frightened child showing a worried expression";
            case "dancar" -> "a person dancing with their full body visible";
            case "festa" -> "people celebrating a birthday party around a cake";
            case "chocolate" -> "a chocolate bar broken into visible pieces";
            case "casa" -> "the front of a family home";
            case "estudar" -> temaEscolar
                    ? "a student studying at a classroom desk"
                    : "a student studying at a desk";
            case "ler" -> "a child reading an open book";
            case "escrever" -> "a child writing in a notebook with a pencil";
            case "comer" -> "a person eating a meal at a table";
            case "beber" -> "a person drinking water from a clear glass";
            case "dormir" -> "a child sleeping peacefully in bed";
            case "correr" -> "a child running outdoors with full body visible";
            case "pular", "saltar" -> "a child jumping with full body visible";
            case "bola" -> "a colorful ball on a playground";
            case "livro" -> "an open illustrated children's book";
            case "mochila" -> "a colorful school backpack";
            case "professor", "professora" -> "a teacher standing in a classroom";
            case "aluno", "aluna", "estudante" -> "a student sitting at a classroom desk";
            case "agua" -> "a clear glass filled with drinking water";
            case "cachorro" -> "a friendly dog sitting and looking at the camera";
            case "gato" -> "a domestic cat sitting and looking at the camera";
            default -> null;
        };
    }


    /*
     * =========================================================
     * NORMALIZA NOME DO ARQUIVO
     * =========================================================
     *
     * Exemplo:
     *
     * "dançar"
     *      ↓
     * "dancar"
     *
     * "ação"
     *      ↓
     * "acao"
     *
     * A normalização funciona em nomes simples com acentos e espaços, mas palavras
     * diferentes podem colidir após perder acentos ou pontuação. O contrato atual
     * aceita esse risco para manter nomes legíveis e portáveis entre sistemas.
     */

    private static String normalizarNomeArquivo(
            String palavra
    ) {

        String normalizada =
                Normalizer.normalize(
                        palavra,
                        Normalizer.Form.NFD
                );


        normalizada =
                normalizada.replaceAll(
                        "\\p{M}",
                        ""
                );


        normalizada =
                normalizada
                        .toLowerCase(Locale.ROOT)
                        .replaceAll(
                                "[^a-z0-9]+",
                                "_"
                        )
                        .replaceAll(
                                "^_+|_+$",
                                ""
                        );


        return normalizada;
    }


    /**
     * Confirma que o serviço local responde antes de iniciar as chamadas de texto.
     * Esse teste rápido melhora a mensagem de erro, embora não garanta que o modelo
     * esteja instalado nem que uma geração futura vá terminar com sucesso.
     */
    private static void verificarOllama()
            throws Exception {

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        OLLAMA_HOST
                                                + "/api/tags"
                                )
                        )
                        .timeout(
                                Duration.ofSeconds(30)
                        )
                        .GET()
                        .build();


        HttpResponse<String> response =
                HTTP_CLIENT.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );


        if (
                response.statusCode() != 200
        ) {

            throw new IllegalStateException(
                    "Não foi possível conectar ao Ollama. "
                            + "Código HTTP: "
                            + response.statusCode()
            );
        }


        System.out.println(
                "Ollama conectado com sucesso."
        );
    }


    /**
     * Procura o nome exato do modelo no Ollama e baixa-o se estiver ausente.
     * O download automático simplifica a primeira execução; em troca, exige rede
     * e espaço em disco nessa primeira vez. Depois disso, a geração é local.
     */
    private static void verificarModelo()
            throws Exception {

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        OLLAMA_HOST
                                                + "/api/tags"
                                )
                        )
                        .timeout(
                                Duration.ofSeconds(30)
                        )
                        .GET()
                        .build();


        HttpResponse<String> response =
                HTTP_CLIENT.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );


        if (
                response.statusCode() != 200
        ) {

            throw new IllegalStateException(
                    "Não foi possível consultar "
                            + "os modelos do Ollama."
            );
        }


        JsonNode raiz =
                MAPPER.readTree(
                        response.body()
                );


        JsonNode modelos =
                raiz.get("models");


        boolean modeloExiste = false;


        if (
                modelos != null
                        && modelos.isArray()
        ) {

            for (
                    JsonNode modelo : modelos
            ) {

                String nome =
                        modelo
                                .path("name")
                                .asText();


                if (
                        nome.equals(MODELO)
                ) {

                    modeloExiste = true;

                    break;
                }
            }
        }


        if (modeloExiste) {

            System.out.println(
                    "Modelo encontrado: "
                            + MODELO
            );

            return;
        }


        System.out.println(
                "Modelo não encontrado."
        );


        System.out.println(
                "Baixando modelo. Isso pode demorar..."
        );


        String body =
                """
                {
                  "name": "%s",
                  "stream": false
                }
                """.formatted(
                        MODELO
                );


        HttpRequest pullRequest =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        OLLAMA_HOST
                                                + "/api/pull"
                                )
                        )
                        .timeout(
                                Duration.ofMinutes(30)
                        )
                        .header(
                                "Content-Type",
                                "application/json"
                        )
                        .POST(
                                HttpRequest.BodyPublishers
                                        .ofString(body)
                        )
                        .build();


        HttpResponse<String> pullResponse =
                HTTP_CLIENT.send(
                        pullRequest,
                        HttpResponse.BodyHandlers.ofString()
                );


        if (
                pullResponse.statusCode() != 200
        ) {

            throw new IllegalStateException(
                    "Falha ao baixar o modelo. "
                            + "Código HTTP: "
                            + pullResponse.statusCode()
                            + "\nResposta: "
                            + pullResponse.body()
            );
        }


        System.out.println(
                "Modelo baixado com sucesso."
        );
    }


    /**
     * Pede uma lista de opções relacionadas ao tema, sem exigir JSON nesta etapa.
     * Texto livre é mais simples para o modelo pequeno produzir; a seleção posterior
     * é quem impõe o formato estruturado e a quantidade final.
     */
    private static String gerarCandidatos(
            String pedido
    ) throws Exception {

        String prompt =
                """
                Gere vocabulário para uma prancha de Comunicação Aumentativa e Alternativa.

                Tema: %s

                Gere exatamente %d candidatos semanticamente relacionados ao tema.

                Regras:
                - Escreva uma palavra ou expressão curta por linha.
                - Priorize substantivos e verbos úteis para comunicação.
                - Não explique nada.
                - Não use numeração.
                - Evite repetições.
                """.formatted(
                        pedido,
                        QUANTIDADE_CANDIDATOS
                );


        return chamarOllama(
                prompt,
                512,
                0.2,
                null
        );
    }


    /**
     * Escolhe exatamente N candidatos e solicita um tipo gramatical permitido.
     * O schema reduz respostas malformadas e as tentativas recuperam falhas leves.
     * A validação abaixo ainda é necessária, pois formato válido não garante sentido.
     */
    private static List<CandidatoIA> selecionarPalavras(
            String pedido,
            String candidatos
    ) throws Exception {

        String candidatosLimpos =
                limitarTexto(
                        candidatos,
                        2500
                );


        String prompt =
                """
                Selecione palavras para uma prancha de Comunicação Aumentativa e Alternativa.

                Tema: %s

                Candidatos:
                %s

                Escolha exatamente %d palavras muito relacionadas ao tema.

                Regras:
                - Use somente palavras em português.
                - Cada palavra deve ter apenas uma palavra, sem espaços.
                - Não use frases.
                - Não repita palavras.
                - Use somente substantivos ou verbos relevantes ao tema.
                - O campo tipo deve ser:
                  v para verbo,
                  s para substantivo.
                - Não use nenhum outro tipo além de v ou s.
                - Não explique nada.
                - Retorne somente um array JSON.
                - O array deve conter exatamente %d objetos.
                - Cada objeto deve possuir somente os campos palavra e tipo.

                Formato obrigatório:
                [
                  {"palavra":"video","tipo":"s"},
                  {"palavra":"dancar","tipo":"v"}
                ]
                """.formatted(
                        pedido,
                        candidatosLimpos,
                        QUANTIDADE_PALAVRAS,
                        QUANTIDADE_PALAVRAS
                );


        Object esquemaSelecao =
                construirEsquemaSelecao();


        for (
                int tentativa = 1;
                tentativa <= MAX_TENTATIVAS_JSON;
                tentativa++
        ) {

            System.out.println();

            System.out.println(
                    "Tentativa de geração do JSON: "
                            + tentativa
                            + "/"
                            + MAX_TENTATIVAS_JSON
            );


            String resposta =
                    chamarOllama(
                            prompt,
                            1024,
                            0.2,
                            esquemaSelecao
                    );


            System.out.println();
            System.out.println(
                    "Resposta recebida:"
            );

            System.out.println(
                    resposta
            );


            List<CandidatoIA> resultado =
                    parseRespostaIA(
                            resposta
                    );


            if (
                    resultado.size()
                            == QUANTIDADE_PALAVRAS
            ) {

                System.out.println(
                        "JSON válido com exatamente "
                                + QUANTIDADE_PALAVRAS
                                + " palavras."
                );

                return resultado;
            }


            System.out.println(
                    "A resposta não contém exatamente "
                            + QUANTIDADE_PALAVRAS
                            + " palavras válidas."
            );
        }


        return List.of();
    }


    /**
     * Monta o JSON Schema aceito pelo endpoint Ollama.
     * Declarar enum e tamanho no próprio pedido evita depender só de instruções em
     * texto; a checagem Java continua como segunda barreira para modelos incompatíveis.
     */
    private static Map<String, Object>
    construirEsquemaSelecao() {

        Map<String, Object>
                propriedadePalavra =
                new LinkedHashMap<>();


        propriedadePalavra.put(
                "type",
                "string"
        );


        Map<String, Object>
                propriedadeTipo =
                new LinkedHashMap<>();


        propriedadeTipo.put(
                "type",
                "string"
        );


        propriedadeTipo.put(
                "enum",
                List.of("v", "s")
        );


        Map<String, Object>
                propriedades =
                new LinkedHashMap<>();


        propriedades.put(
                "palavra",
                propriedadePalavra
        );


        propriedades.put(
                "tipo",
                propriedadeTipo
        );


        Map<String, Object>
                item =
                new LinkedHashMap<>();


        item.put(
                "type",
                "object"
        );


        item.put(
                "properties",
                propriedades
        );


        item.put(
                "required",
                List.of(
                        "palavra",
                        "tipo"
                )
        );


        item.put(
                "additionalProperties",
                false
        );


        Map<String, Object>
                esquema =
                new LinkedHashMap<>();


        esquema.put(
                "type",
                "array"
        );


        esquema.put(
                "items",
                item
        );


        esquema.put(
                "minItems",
                QUANTIDADE_PALAVRAS
        );


        esquema.put(
                "maxItems",
                QUANTIDADE_PALAVRAS
        );


        return esquema;
    }


    /**
     * Envia uma geração não transmitida em partes e devolve apenas o texto produzido.
     * O modo não-streaming simplifica parsing e validação; o custo é a interface não
     * receber tokens parciais. O Task do JavaFX mantém a janela responsiva durante a espera.
     */
    private static String chamarOllama(
            String prompt,
            int maxTokens,
            double temperatura,
            Object formato
    ) throws Exception {

        ObjectMapper mapper =
                new ObjectMapper();


        Map<String, Object> body =
                new LinkedHashMap<>();


        body.put(
                "model",
                MODELO
        );


        body.put(
                "prompt",
                prompt
        );


        body.put(
                "stream",
                false
        );


        body.put(
                "think",
                false
        );


        if (formato != null) {

            body.put(
                    "format",
                    formato
            );
        }


        Map<String, Object> options =
                new LinkedHashMap<>();


        options.put(
                "temperature",
                temperatura
        );


        options.put(
                "top_p",
                0.85
        );


        options.put(
                "repeat_penalty",
                1.15
        );


        options.put(
                "num_predict",
                maxTokens
        );


        body.put(
                "options",
                options
        );


        String jsonBody =
                mapper.writeValueAsString(
                        body
                );


        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        OLLAMA_HOST
                                                + "/api/generate"
                                )
                        )
                        .timeout(
                                Duration.ofMinutes(10)
                        )
                        .header(
                                "Content-Type",
                                "application/json"
                        )
                        .POST(
                                HttpRequest.BodyPublishers
                                        .ofString(jsonBody)
                        )
                        .build();


        HttpResponse<String> response =
                HTTP_CLIENT.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );


        if (
                response.statusCode() != 200
        ) {

            throw new IllegalStateException(
                    "Erro na chamada ao Ollama. "
                            + "Código HTTP: "
                            + response.statusCode()
                            + "\nResposta: "
                            + response.body()
            );
        }


        JsonNode raiz =
                mapper.readTree(
                        response.body()
                );


        String texto =
                raiz
                        .path("response")
                        .asText();


        if (texto.isBlank()) {

            throw new IllegalStateException(
                    "O Ollama retornou uma resposta vazia."
            );
        }


        return limparTokensEspeciais(
                texto
        );
    }


    /**
     * Converte apenas itens com palavra alfabética, tipo permitido e chave única.
     * Rejeitar itens malformados mantém o formato seguro para a interface; a regra
     * de uma palavra sem hífen limita expressões compostas que poderiam ser úteis.
     */
    private static List<CandidatoIA> parseRespostaIA(
            String resposta
    ) {

        try {

            String json =
                    extrairJSON(
                            resposta
                    );


            JsonNode raiz =
                    MAPPER.readTree(
                            json
                    );


            JsonNode array;


            if (raiz.isArray()) {

                array = raiz;

            } else if (
                    raiz.has("itens")
                            && raiz
                            .get("itens")
                            .isArray()
            ) {

                array =
                        raiz.get("itens");

            } else {

                System.out.println(
                        "A resposta JSON não é um array "
                                + "e não possui o campo 'itens'."
                );

                return List.of();
            }


            Map<String, CandidatoIA>
                    palavrasUnicas =
                    new LinkedHashMap<>();


            for (
                    JsonNode item : array
            ) {

                if (!item.isObject()) {
                    continue;
                }


                JsonNode palavraNode =
                        item.get("palavra");


                JsonNode tipoNode =
                        item.get("tipo");


                if (
                        palavraNode == null
                                || tipoNode == null
                ) {

                    continue;
                }


                String palavra =
                        palavraNode
                                .asText("")
                                .strip()
                                .toLowerCase(
                                        Locale.ROOT
                                );


                String tipo =
                        tipoNode
                                .asText("")
                                .strip()
                                .toLowerCase(
                                        Locale.ROOT
                                );


                if (palavra.isBlank()) {
                    continue;
                }


                if (
                        !palavra.matches(
                                "^[\\p{L}]+$"
                        )
                ) {

                    System.out.println(
                            "Palavra rejeitada por conter "
                                    + "espaços ou símbolos: "
                                    + palavra
                    );

                    continue;
                }


                if (
                        !TIPOS_PERMITIDOS
                                .contains(tipo)
                ) {

                    System.out.println(
                            "Tipo inválido para a palavra "
                                    + "(apenas v/s são aceitos): "
                                    + palavra
                                    + " -> "
                                    + tipo
                    );

                    continue;
                }


                palavrasUnicas.putIfAbsent(
                        palavra,
                        new CandidatoIA(
                                palavra,
                                tipo
                        )
                );


                if (
                        palavrasUnicas.size()
                                == QUANTIDADE_PALAVRAS
                ) {

                    break;
                }
            }


            return new ArrayList<>(
                    palavrasUnicas.values()
            );

        } catch (Exception e) {

            System.out.println(
                    "Erro ao interpretar JSON: "
                            + e.getMessage()
            );

            return List.of();
        }
    }


    /**
     * Tolera cercas Markdown e texto introdutório comuns em respostas de LLM.
     * Procurar o primeiro e o último delimitador é simples, mas uma resposta com
     * JSON extra pode continuar ambígua e será rejeitada pelo parser.
     */
    private static String extrairJSON(
            String resposta
    ) {

        String texto =
                limparTokensEspeciais(
                        resposta
                ).strip();


        int inicioArray =
                texto.indexOf("[");


        int fimArray =
                texto.lastIndexOf("]");


        if (
                inicioArray >= 0
                        && fimArray > inicioArray
        ) {

            return texto.substring(
                    inicioArray,
                    fimArray + 1
            );
        }


        int inicioObjeto =
                texto.indexOf("{");


        int fimObjeto =
                texto.lastIndexOf("}");


        if (
                inicioObjeto >= 0
                        && fimObjeto > inicioObjeto
        ) {

            return texto.substring(
                    inicioObjeto,
                    fimObjeto + 1
            );
        }


        throw new IllegalArgumentException(
                "Nenhum array ou objeto JSON encontrado."
        );
    }


    /** Remove marcadores de modelo e cercas de código antes do parsing ou uso do texto. */
    private static String limparTokensEspeciais(
            String texto
    ) {

        if (texto == null) {
            return "";
        }


        return texto
                .replace(
                        "<|begin_of_text|>",
                        ""
                )
                .replace(
                        "<|end_of_text|>",
                        ""
                )
                .replace(
                        "<|start_header_id|>",
                        ""
                )
                .replace(
                        "<|end_header_id|>",
                        ""
                )
                .replace(
                        "<|eot_id|>",
                        ""
                )
                .replace(
                        "```json",
                        ""
                )
                .replace(
                        "```",
                        ""
                )
                .strip();
    }


    /**
     * Limita o texto candidato para controlar o tamanho do prompt de seleção.
     * Isso protege o custo e o limite de contexto do modelo; se a lista for longa,
     * o truncamento pode descartar candidatos que apareceram no final.
     */
    private static String limitarTexto(
            String texto,
            int limite
    ) {

        if (texto == null) {
            return "";
        }


        if (texto.length() <= limite) {
            return texto;
        }


        return texto.substring(
                0,
                limite
        );
    }


    /**
     * Par palavra/tipo que atravessa as etapas de geração e a interface.
     * O tipo é guardado como "v" ou "s" para manter o JSON pequeno e compatível
     * com a cor aplicada à palavra na prancha.
     */
    public record CandidatoIA(
            String palavra,
            String tipo
    ) {
    }


    /**
     * Formato persistido em prancha-gerada.json. Guardar o pedido original junto
     * dos itens permite contextualizar a geração de imagens numa rodada posterior.
     */
    private record Prancha(
            String pedidoOriginal,
            List<CandidatoIA> itens
    ) {
    }
}
