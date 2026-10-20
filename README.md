# Files Media

**Um só lugar para ficheiros, música, vídeos e PDFs.**

Files Media é uma aplicação Android gratuita em desenvolvimento. O objetivo é conquistar público com utilidade real, simplicidade, privacidade e bom desempenho — não maximizar receitas.

## O que estamos a construir

### Gestor de ficheiros
- Categorias para imagens, vídeos, áudio, documentos, transferências e todos os ficheiros acessíveis.
- Pesquisa por nome e ordenação por data, nome ou tamanho.
- Favoritos, ficheiros grandes, recentes e resumo de armazenamento.
- Abrir, partilhar, mudar nome, copiar, mover e eliminar, respeitando as permissões concedidas pelo Android.
- Exploração de pastas com o Storage Access Framework.

### Música e vídeo
- Reprodução através do AndroidX Media3.
- Fila de reprodução para itens de áudio/vídeo descobertos ou selecionados.
- Controlos de áudio com avanço/retrocesso e barra de progresso.
- Velocidade ajustável, repetição, reprodução aleatória e temporizador de suspensão.
- Seleção de legendas externas em vídeo (SRT/VTT, conforme o tipo de ficheiro e o descodificador).
- Reprodução em segundo plano através de uma sessão multimédia.

### PDF
- Abrir PDFs através do seletor de documentos do Android.
- Guardar uma lista local dos PDFs escolhidos e restaurar a última página lida.
- Zoom por pinça, salto direto para página, partilha e opção de abrir noutro leitor.
- Renderização com o PdfRenderer do Android, sem acrescentar um motor PDF grande ao MVP.

**Nota importante:** alguns PDFs e ficheiros da pasta Downloads não aparecem no índice MediaStore em versões modernas do Android. Por isso, o ecrã Documentos oferece também a opção **Abrir PDF do dispositivo**; os documentos escolhidos ficam disponíveis em Recentes e na lista de PDFs.

## Interface e privacidade

- Interface em português, com pesquisa no topo, cartões de recentes, categorias, coleções e armazenamento.
- Sem conta obrigatória e sem servidor necessário para as funções principais.
- Sem anúncios como prioridade inicial; as decisões devem ajudar a conquistar e manter utilizadores.
- As pastas são acedidas através dos mecanismos permitidos pelo Android. A aplicação não promete acesso irrestrito a diretórios protegidos do sistema.
- Nada deve ser eliminado automaticamente: as sugestões de limpeza deixam o utilizador rever e confirmar as ações.

## Estado atual

O projeto está em desenvolvimento e ainda requer testes em dispositivos físicos. O repositório contém:
- Projeto Android nativo em Kotlin.
- Leitor multimédia baseado em Media3.
- Navegação de pastas e operações de ficheiros.
- Leitor PDF baseado no PdfRenderer.
- Ecrãs de armazenamento e sugestões de limpeza.
- Workflow GitHub Actions para testar e compilar um APK de depuração.

As funcionalidades assinaladas no código não devem ser consideradas validadas apenas porque compilam. Os próximos passos são verificar permissões e PDFs em diferentes versões do Android, testar formatos reais de áudio/vídeo, resolver erros de operações de ficheiros e aperfeiçoar a interface.

## Compilação

Requisitos de desenvolvimento:
- JDK 17
- Android SDK Platform 36
- Gradle 8.13
- Android Studio recente

Abre o repositório no Android Studio e sincroniza o Gradle. A compilação de CI produz um APK de depuração como artefacto temporário; não é uma versão assinada para publicação.

## Naruto e ficheiros de teste

Podes testar com ficheiros de Naruto que já tenhas no dispositivo e para os quais tenhas direito de reprodução. Não vamos incluir episódios, logótipos, capturas do anime ou imagens protegidas de Naruto nos recursos distribuídos da aplicação. As miniaturas podem ser lidas localmente a partir dos teus próprios ficheiros.

## Como reportar problemas

Inclui, sempre que possível:
- Modelo e versão do Android.
- Tipo de ficheiro e onde está guardado (MediaStore, Downloads ou pasta escolhida).
- Passos para reproduzir o problema.
- Mensagem de erro e captura de ecrã sem informação privada.

O nosso critério de sucesso é tornar Files Media útil, leve, bonito, acessível e fiável, conquistando utilizadores através da qualidade.
