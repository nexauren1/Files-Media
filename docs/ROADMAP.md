# Files Media — funcionalidades e prioridades

Objetivo principal: tornar o Files Media útil, leve, bonito e confiável para conquistar utilizadores. A monetização não é prioridade.

## P0 — Corrigir os problemas fundamentais

- [x] Ecrã inicial com pesquisa, recentes, categorias e resumo de armazenamento.
- [x] Navegação por pastas escolhidas explicitamente pelo utilizador com Storage Access Framework.
- [x] Favoritos locais e opções de ordenação por nome, data e tamanho.
- [x] Ação explícita para escolher um PDF quando o Android não o apresentar no índice multimédia.
- [x] Lista local dos PDFs abertos através do seletor de documentos.
- [x] Leitor PDF com zoom por pinça, salto para uma página, partilha e posição de leitura guardada.
- [x] UI própria para o leitor de música, lista de reprodução e controlos de progresso.
- [x] Opções de velocidade, repetição, aleatório, legendas externas e temporizador no leitor multimédia.
- [ ] Testar em dispositivos reais: permissões, PDFs locais/Downloads/SD, áudio em segundo plano, vídeo e ficheiros grandes.
- [ ] Verificar comportamento com PDFs protegidos, URI revogado, MIME incorreto e documentos de fornecedores externos.
- [ ] Garantir que mover/copy/delete explicam as permissões e nunca removem dados sem confirmação clara.

## P1 — Melhorias de alto valor para o público

### Gestor de ficheiros
- Alternar vista de lista/grelha e definir a preferência do utilizador.
- Seleção múltipla com barra de ações: copiar, mover, partilhar e eliminar.
- Breadcrumbs para a pasta atual, acesso rápido às pastas comuns e botão para voltar à raiz escolhida.
- Pesquisa unificada por nome e filtros por tipo, extensão, tamanho e data.
- Informações detalhadas: tamanho, tipo MIME, localização, data e permissões disponíveis.
- Operações de lote seguras e mensagens de erro legíveis.
- Favoritos persistentes para qualquer URI suportada, com opção de remover itens cujo acesso foi revogado.

### Música
- Fila editável, adicionar/remover itens, próxima/anterior e manter a fila ao mudar de ecrã.
- Reprodução em segundo plano e controlos de notificação/ecrã bloqueado.
- Velocidade, temporizador, repetição de faixa/lista, aleatório e retomar a posição.
- Ler metadados e arte incorporada quando disponíveis, sem fazer grandes cópias na memória.
- Equalizador apenas numa fase posterior, utilizando efeitos Android compatíveis e verificando se o dispositivo os suporta.

### Vídeo
- Legendas incorporadas e carregamento de SRT/VTT externos.
- Ajustar ao ecrã, ajustar à largura, preencher/cortar e rotação.
- Saltar 10 segundos, controlo de velocidade, retomar posição e fila de vídeos.
- Picture-in-picture como melhoria opcional, apenas após validar o ciclo de vida e as regras do Android.
- Seleção de faixas de áudio e legendas quando o ficheiro as contém.

### PDF
- Miniaturas, favoritos e documentos recentes.
- Zoom por pinça e ajuste à página.
- Saltar para a página e restaurar a última posição.
- Partilhar e abrir em outro leitor.
- Pesquisa de texto e anotações exigem uma análise própria: o PdfRenderer do sistema é leve, mas não fornece extração de texto nem um conjunto completo de ferramentas de edição.

## P2 — Utilidades seguras e diferenciadoras

- Análise de armazenamento por categoria com explicação clara das limitações dos dados fornecidos pelo sistema.
- Sugestões para ficheiros grandes e pastas de transferências, sempre com seleção e confirmação manual.
- Arquivar/desarquivar ZIP como módulo opcional, com limites contra arquivos malformados e sem extrair silenciosamente sobre ficheiros existentes.
- Cofre privado local apenas depois de definir corretamente cifragem, chaves, recuperação e comportamento de cópias de segurança.
- Acessibilidade: descrições para ícones, áreas de toque grandes, contraste e suporte a tamanhos de letra maiores.
- Idiomas: português primeiro; acrescentar inglês e outras traduções com base em pedidos reais.

## Princípios técnicos

- Preferir APIs Android oficiais e bibliotecas já necessárias.
- Usar MediaStore para áudio/vídeo/imagens indexados e Storage Access Framework para documentos/pastas que exigem seleção explícita.
- Nunca afirmar que a app pode navegar livremente em qualquer diretório protegido: o acesso depende da versão do Android e das autorizações concedidas.
- Manter operações intensivas fora da thread principal; limitar bitmaps e fechar recursos ao sair.
- Não incluir episódios, imagens, logos ou outros ativos protegidos de Naruto no APK. Os ficheiros que o utilizador já possui podem ser usados localmente para testes.
- Sem anúncios ou pagamentos como prioridade: a métrica principal é utilidade, estabilidade, recomendações e satisfação dos utilizadores.

## Referências oficiais

- [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)
- [Acesso a ficheiros multimédia através do MediaStore](https://developer.android.com/training/data-storage/shared/media)
- [Media3 ExoPlayer](https://developer.android.com/media/media3/exoplayer/hello-world)
- [Media3 — listas de reprodução e controlos](https://developer.android.com/media/media3/session/player)
- [PdfRenderer](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer)

Este roteiro é uma lista de trabalho. Os itens assinalados como concluídos indicam implementação no código, não testes completos em dispositivos físicos.
