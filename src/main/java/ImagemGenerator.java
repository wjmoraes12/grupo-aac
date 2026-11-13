import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Executa a geração local de uma imagem e valida que o arquivo foi criado.
 *
 * A classe delega a difusão ao executável sd-cli em vez de carregar o modelo
 * dentro da JVM. Isso mantém a integração pequena e compatível com a instalação
 * local existente, mas cada foto inicia um processo novo e depende dos caminhos
 * específicos abaixo. O modelo também é carregado novamente a cada chamada.
 */
public class ImagemGenerator {

    // Caminho local adotado pelo projeto. A vantagem é não exigir download em
    // toda execução; a desvantagem é que outra instalação precisa ajustar o caminho.
    private static final Path SD_CLI =
            Path.of(
                    System.getProperty("user.home"),
                    "stable-diffusion.cpp",
                    "build",
                    "bin",
                    "sd-cli"
            );

    // O SD 1.5 foi mantido para aproveitar o modelo que já existe no computador.
    // Ele é leve em comparação com modelos maiores, mas pode errar detalhes visuais.
    private static final Path MODEL =
            Path.of(
                    System.getProperty("user.home"),
                    "stable-diffusion.cpp",
                    "models",
                    "v1-5-pruned-emaonly.safetensors"
            );

    /**
     * Gera uma foto para a descrição visual em inglês recebida em {@code palavra}.
     * O destino é sobrescrito pelo sd-cli se já existir.
     *
     * @param palavra descrição curta do assunto principal, preparada em acc.java
     * @param destino caminho PNG associado à palavra portuguesa da prancha
     * @throws Exception se os arquivos locais faltarem ou o processo falhar
     */
    public static void gerar(
            String palavra,
            Path destino
    ) throws Exception {

        validarArquivos();

        Files.createDirectories(destino.getParent());

        // A descrição principal e a lista negativa são mantidas separadas porque
        // o sd-cli trata cada uma com pesos diferentes durante a amostragem.
        String prompt = criarPrompt(palavra);
        String negativePrompt = criarNegativePrompt();

        System.out.println();
        System.out.println("=================================");
        System.out.println("Gerando imagem");
        System.out.println("Palavra (prompt): " + palavra);
        System.out.println("Destino: " + destino);
        System.out.println("=================================");

        // 512x512 corresponde à resolução nativa do SD 1.5 e reduz o consumo
        // de memória. O custo é menos detalhe que um modelo e uma resolução maiores.
        // DPM++ 2M com 35 etapas favorece acabamento; em CPU, cada foto pode demorar.
        // CFG 7 mantém aderência ao assunto sem forçar tanto o prompt.
        // Seed -1 pede uma seed aleatória. A seed padrão fixa do sd-cli repetia
        // a composição entre palavras; aleatoriedade evita isso, mas reduz repetibilidade.
        ProcessBuilder processBuilder =
                new ProcessBuilder(
                        SD_CLI.toString(),

                        "-m",
                        MODEL.toString(),

                        "-p",
                        prompt,

                        "-n",
                        negativePrompt,

                        "-W",
                        "512",

                        "-H",
                        "512",

                        "--steps",
                        "35",

                        "--cfg-scale",
                        "7",

                        "--sampling-method",
                        "dpm++2m",

                        "--seed",
                        "-1",

                        "-o",
                        destino.toString()
                );

        // Reúne stdout e stderr para que o log do processo apareça numa única sequência.
        processBuilder.redirectErrorStream(true);

        Process processo = processBuilder.start();

        try (
                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        processo.getInputStream()
                                )
                        )
        ) {

            String linha;

            while ((linha = reader.readLine()) != null) {

                System.out.println(
                        "[Stable Diffusion] " + linha
                );
            }
        }

        int codigo = processo.waitFor();

        if (codigo != 0) {

            throw new RuntimeException(
                    "Stable Diffusion terminou com código "
                            + codigo
            );
        }

        if (!Files.exists(destino)) {

            throw new RuntimeException(
                    "Stable Diffusion terminou sem gerar a imagem: "
                            + destino
            );
        }

        System.out.println(
                "Imagem gerada com sucesso: "
                        + destino
        );
    }

    /**
     * Confere os pré-requisitos antes de iniciar um processo demorado.
     *
     * A verificação local preserva o modo offline e não envia o tema a terceiros;
     * em contrapartida, não instala nem localiza automaticamente outros modelos.
     */
    private static void validarArquivos() {

        if (!Files.exists(SD_CLI)) {

            throw new IllegalStateException(
                    "Executável do Stable Diffusion não encontrado: "
                            + SD_CLI
            );
        }

        if (!Files.exists(MODEL)) {

            throw new IllegalStateException(
                    "Modelo não encontrado: "
                            + MODEL
            );
        }
    }

    /**
     * Dá prioridade ao assunto no começo do prompt e pede uma fotografia colorida.
     * O assunto grande em primeiro plano melhora a leitura em cartões pequenos;
     * o cenário fica secundário para reduzir desvios sem remover todo o contexto real.
     * Prompts orientam o modelo, mas não garantem que cada detalhe saia correto.
     */
    private static String criarPrompt(
            String palavra
    ) {

        return """
                Photorealistic documentary photograph. Main subject: %s. \
                Show this exact subject large, clear, and in the foreground, filling most of the square frame. \
                The subject must directly represent the named object, person, or action. \
                Use only a simple, relevant everyday background; keep it secondary and unobtrusive. \
                Natural daylight, realistic colors and materials, lifelike proportions, \
                sharp focus on the subject, authentic camera photograph, medium shot. \
                No text, captions, letters, numbers, labels, or watermark.
                """.formatted(palavra);
    }

    /**
     * Afasta estilos de ícone e erros comuns de composição.
     * Uma lista negativa muito extensa também pode suprimir elementos legítimos,
     * por isso as restrições aqui são gerais e evitam descrever uma cena específica.
     */
    private static String criarNegativePrompt() {

        return """
                icon, pictogram, symbol, clipart, illustration, drawing, cartoon, \
                painting, sketch, 3d render, CGI, digital art, text, watermark, logo, \
                unrelated main subject, unrelated scenery, distant tiny subject, \
                window view, vast empty landscape, cluttered background, collage, \
                blurry, low resolution, plastic skin, oversaturated colors, \
                distorted anatomy, extra limbs, cropped subject, duplicate
                """;
    }
}
