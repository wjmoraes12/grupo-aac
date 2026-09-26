import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;

public class ImagemGenerator {

    private static final Path SD_CLI =
            Path.of(
                    System.getProperty("user.home"),
                    "stable-diffusion.cpp",
                    "build",
                    "bin",
                    "sd-cli"
            );

    private static final Path MODEL =
            Path.of(
                    System.getProperty("user.home"),
                    "stable-diffusion.cpp",
                    "models",
                    "v1-5-pruned-emaonly.safetensors"
            );

    /*
     * Limiar de luminância (0-255) usado no pós-processamento.
     * Pixels mais claros que isso viram branco puro,
     * mais escuros viram preto puro.
     */
    private static final int LIMIAR_THRESHOLD = 170;

    public static void gerar(
            String palavra,
            Path destino
    ) throws Exception {

        validarArquivos();

        Files.createDirectories(destino.getParent());

        String prompt = criarPrompt(palavra);
        String negativePrompt = criarNegativePrompt();

        System.out.println();
        System.out.println("=================================");
        System.out.println("Gerando imagem");
        System.out.println("Palavra (prompt): " + palavra);
        System.out.println("Destino: " + destino);
        System.out.println("=================================");

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
                        "25",

                        "--cfg-scale",
                        "9",

                        "--sampling-method",
                        "euler_a",

                        "-o",
                        destino.toString()
                );

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
                "Aplicando pós-processamento (threshold preto/branco)..."
        );

        aplicarThreshold(destino);

        System.out.println(
                "Imagem gerada com sucesso: "
                        + destino
        );
    }

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

    /*
     * =========================================================
     * PÓS-PROCESSAMENTO
     * =========================================================
     *
     * O SD1.5 é um modelo fotorealista por natureza. Mesmo com
     * prompt pedindo "flat vector icon", ele quase sempre deixa
     * cinzas, sombras e ruído na imagem. Este passo converte a
     * imagem para preto e branco puro, aproximando o resultado
     * de um pictograma limpo, independente de quão "suja"
     * a geração tenha saído.
     */

    private static void aplicarThreshold(
            Path destino
    ) throws Exception {

        File arquivo = destino.toFile();

        BufferedImage original = ImageIO.read(arquivo);

        if (original == null) {

            throw new RuntimeException(
                    "Não foi possível ler a imagem gerada para pós-processar: "
                            + destino
            );
        }

        int largura = original.getWidth();
        int altura = original.getHeight();

        BufferedImage resultado =
                new BufferedImage(
                        largura,
                        altura,
                        BufferedImage.TYPE_INT_RGB
                );

        for (int y = 0; y < altura; y++) {

            for (int x = 0; x < largura; x++) {

                int rgb = original.getRGB(x, y);

                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;

                double luminancia =
                        (0.299 * r) + (0.587 * g) + (0.114 * b);

                int corFinal =
                        luminancia >= LIMIAR_THRESHOLD
                                ? 0xFFFFFF
                                : 0x000000;

                resultado.setRGB(x, y, corFinal);
            }
        }

        ImageIO.write(resultado, "png", arquivo);
    }

    private static String criarPrompt(
            String palavra
    ) {

        return """
                flat vector icon of %s, \
                pictogram style, single isolated subject, \
                solid black silhouette on white background, \
                simple geometric shapes, bold outlines, \
                2D flat design, icon set style, \
                symmetrical, centered, minimal, \
                clean vector art, no gradients, no shading, \
                no text, no letters, no numbers, no watermark
                """.formatted(palavra);
    }

    private static String criarNegativePrompt() {

        return """
                realistic, photorealistic, 3d render, photo, \
                text, letters, numbers, words, watermark, signature, \
                logo, multiple objects, cluttered, complex background, \
                gradient, shading, texture, noise, blurry, \
                low quality, low resolution, jpeg artifacts, \
                distorted, deformed, disfigured, extra limbs, \
                asymmetrical, cropped, out of frame, ugly, duplicate
                """;
    }
}