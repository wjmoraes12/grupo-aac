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

public class acc {

    private static final String OLLAMA_HOST =
            "http://localhost:11434";

    private static final String MODELO =
            "hf.co/tardellirs/aac-board-generator-770m-ptbr-GGUF:Q6_K";

    private static final int QUANTIDADE_PALAVRAS = 3;

    private static final int QUANTIDADE_CANDIDATOS = 25;

    private static final int MAX_TENTATIVAS_JSON = 3;

    private static final Set<String> TIPOS_PERMITIDOS =
            Set.of("v", "s");

    private static final HttpClient HTTP_CLIENT =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();

    private static final ObjectMapper MAPPER =
            new ObjectMapper();

    /*
     * Pasta onde as imagens geradas pelo Stable Diffusion
     * serão armazenadas.
     */
    private static final Path DIRETORIO_IMAGENS =
            Path.of("imagens");


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
         */

        System.out.println();
        System.out.println(
                "Etapa 3/3 - Gerando imagens..."
        );


        gerarImagens(
                palavrasSelecionadas
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


    /*
     * =========================================================
     * GERA AS IMAGENS
     * =========================================================
     */

    private static void gerarImagens(
            List<CandidatoIA> palavras
    ) throws Exception {

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

            System.out.println(
                    "Imagem "
                            + (i + 1)
                            + "/"
                            + palavras.size()
                            + ": "
                            + palavra
            );


            String palavraEmIngles =
                    traduzirParaIngles(
                            palavra
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
     * TRADUZ A PALAVRA PARA INGLÊS ANTES DE USAR NO PROMPT
     * =========================================================
     *
     * O Stable Diffusion 1.5 foi treinado majoritariamente com
     * prompts em inglês. Inserir a palavra em português direto
     * no template em inglês faz o modelo não reconhecer o
     * conceito e gerar composições genéricas sem relação com
     * a palavra pedida. Este passo traduz antes de montar o
     * prompt de imagem, mantendo o português para o nome do
     * arquivo e para o JSON da prancha.
     */

    private static String traduzirParaIngles(
            String palavra
    ) throws Exception {

        String prompt =
                """
                Translate the Portuguese word "%s" to a single common English word or short phrase, \
                the one most suitable to represent it as a simple visual icon.

                Rules:
                - Respond with ONLY the English word or short phrase.
                - Do not explain anything.
                - Do not add punctuation, quotes or extra text.
                - Do not repeat the Portuguese word.
                """.formatted(
                        palavra
                );

        String resposta =
                chamarOllama(
                        prompt,
                        32,
                        0.1,
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
                        .strip();

        if (limpa.isBlank()) {

            System.out.println(
                    "Falha ao traduzir '"
                            + palavra
                            + "', usando a palavra original."
            );

            return palavra;
        }

        return limpa;
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


    private record CandidatoIA(
            String palavra,
            String tipo
    ) {
    }


    private record Prancha(
            String pedidoOriginal,
            List<CandidatoIA> itens
    ) {
    }
}