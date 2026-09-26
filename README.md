Requisitos Funcionais


Geração de vocabulário (acc.java)


RF01 — O sistema deve receber do usuário uma descrição textual do tema da prancha (ex.: "tiktok", "casa").

RF02 — O sistema deve gerar candidatos de vocabulário relacionados ao tema via LLM local (Ollama).

RF03 — O sistema deve selecionar exatamente N palavras (configurável — já foi 15, 10, 3) relevantes ao tema, cada uma classificada como verbo (v) ou substantivo (s).

RF04 — O sistema deve persistir o resultado em prancha-gerada.json, com o pedido original e a lista de itens (palavra + tipo).

RF05 — O sistema deve validar a resposta da IA e reter apenas o formato exato esperado, rejeitando itens malformados.


Geração de imagens (ImagemGenerator.java, duas linhas de implementação)


RF06 — O sistema deve produzir um pictograma (PNG) por palavra da prancha.

RF07 — (variante ARASAAC) Buscar e baixar o pictograma correspondente de um banco público de símbolos de CAA.

RF07-alt — (variante Stable Diffusion local) Gerar a imagem localmente via modelo de difusão, traduzindo a palavra para inglês antes do prompt e aplicando pós-processamento (threshold preto/branco) para aproximar de um pictograma limpo.

RF08 — O nome do arquivo de imagem deve ser normalizado (sem acentos) para casar de forma confiável com a palavra da prancha.


Interface de comunicação (PranchaApp.java)

RF09 — Exibir em grade apenas os pictogramas que possuem imagem já gerada/baixada.

RF10 — Cada pictograma deve ser codificado visualmente por tipo gramatical (Fitzgerald key: substantivo laranja, verbo verde).

RF11 — Clicar em um pictograma deve inserir a palavra na posição do cursor de um campo de texto editável.

RF12 — O usuário deve poder digitar diretamente no mesmo campo, intercalando com cliques.

RF13 — Clicar em um pictograma deve disparar a fala da palavra em voz alta (síntese de voz em pt-BR).

RF14 — Deve haver controles para falar a frase completa, apagar a última palavra e limpar tudo.



Requisitos Não Funcionais

RNF01 (Privacidade) — Todo o pipeline roda local (Ollama local, imagem local ou API pública sem dados pessoais) — nenhuma informação da criança/usuário de CAA trafega para nuvem de terceiros.

RNF02 (Disponibilidade offline) — Depois de gerada a prancha, o app JavaFX deve funcionar sem internet (imagens e voz são locais).

RNF03 (Portabilidade) — Atualmente não atende: say -v Luciana é exclusivo macOS; a geração de imagem via Stable Diffusion local depende de um binário (sd-cli) compilado e um caminho fixo em ~/stable-diffusion.cpp, ambos amarrados ao ambiente do desenvolvedor.

RNF04 (Desempenho) — Geração de vocabulário deve responder em segundos (modelo de texto 770M); geração de imagem por difusão é ordens de magnitude mais lenta (steps=25, CPU/GPU local) — não é uma operação "interativa".

RNF05 (Confiabilidade) — O formato de saída da IA (JSON) deve ser validado estruturalmente (JSON Schema/structured outputs) porque o modelo de 770M não segue instruções de formato de forma confiável em texto livre.

RNF06 (Usabilidade/Acessibilidade) — Alvo declarado é CAA: alto contraste, alvos de toque grandes, feedback sonoro imediato — tudo isso já influenciou decisões de design.

RNF07 (Manutenibilidade) — O acoplamento entre etapas é feito via arquivo (prancha-gerada.json + pasta imagens/), não uma API interna — troca de implementação de uma etapa não exige mudar as outras, desde que o contrato de arquivo seja mantido.



Tradeoffs discutidos ao longo do processo

1. LLM local (Ollama) vs. API de nuvem
Local ganha em privacidade, custo zero por chamada e funcionamento offline; perde em qualidade/consistência (modelo de 770M erra formato de array com frequência) e em velocidade de geração de imagem comparado a serviços especializados.

2. ARASAAC (Centro Aragonês de Comunicação Aumentativa e Alternativa) vs. geração de imagem por IA
ARASAAC: pictogramas validados para CAA, download instantâneo (KBs), zero infraestrutura local — mas limitado ao vocabulário que já existe no banco.
Geração por IA (Ollama/z-image-turbo, depois Stable Diffusion local): cobre qualquer palavra nova, mas exige ~10GB+ de modelo, gerações lentas, resultado estilo "foto" que precisou de pós-processamento agressivo (threshold p/b) só para se aproximar de um pictograma — no fim, mais esforço de engenharia para um resultado ainda inferior ao ARASAAC em consistência visual. Essa foi a razão prática de você ter voltado para ARASAAC antes dessa nova iteração com Stable Diffusion local aparecer.

3. HTML/web vs. JavaFX desktop
A versão web era zero-instalação e multiplataforma, mas esbarrava em CORS ao ler arquivos locais (imagens/, prancha-gerada.json) sem servidor. JavaFX resolve isso nativamente (acesso direto ao filesystem), ao custo de exigir Maven, plugin específico (javafx-maven-plugin) e não rodar com o botão "Run" padrão do IntelliJ — atrito de desenvolvimento maior.

4. Estrutura de dados de sentença: chips coloridos vs. TextField livre
Chips davam remoção granular por palavra e reforço visual do tipo gramatical, mas não permitiam digitação livre. Você optou por TextField puro para permitir digitar e clicar juntos — trocou granularidade de edição por flexibilidade de entrada.

5. Nomeação de arquivo com acento vs. normalizado
Usar a palavra exata (com acento) como nome de arquivo causava mismatch silencioso entre o que a IA gerava (dançar) e o PNG salvo (dancar.png). A normalização (Normalizer + remoção de diacríticos) resolve à custa de uma pequena chance de colisão entre palavras que só se diferenciam por acento (raro em português, mas existe).
