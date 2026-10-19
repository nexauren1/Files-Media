# Files Media

**Um só lugar para os teus ficheiros, música, vídeos e PDFs.**

Files Media é um projeto Android gratuito que prioriza conquistar utilizadores através de utilidade real, simplicidade, privacidade e bom desempenho — não monetização.

## Objetivos do projeto

- Uma experiência simples, inspirada nos bons padrões de gestores de ficheiros conhecidos, sem copiar marcas, ícones ou ativos de outras aplicações.
- Categorias para imagens, vídeos, áudio, documentos, transferências e ficheiros recentes.
- Reprodução de áudio e vídeo com AndroidX Media3, incluindo uma sessão multimédia para reprodução em segundo plano.
- Leitor PDF inicial baseado no PdfRenderer integrado no Android.
- Exploração de pastas e operações de ficheiros utilizando o Storage Access Framework.
- Interface em português, adaptável e pensada para dispositivos modestos.
- Sem conta obrigatória e sem serviços remotos necessários para as funções principais.
- Sem anúncios como prioridade inicial. A meta é construir confiança e conquistar público.

## Estado atual

Este repositório contém a **primeira fundação técnica / MVP em desenvolvimento**. Ainda necessita de compilação e testes em dispositivos reais antes de ser considerado pronto para publicação.

Implementado nesta base:
- Projeto Android nativo em Kotlin.
- Ecrã inicial com categorias e resumo do armazenamento.
- Consulta de ficheiros indexados pelo MediaStore.
- Navegação de pastas com o seletor de documentos do Android.
- Ações de abrir, partilhar, mudar nome, copiar, mover e eliminar com permissões do sistema quando aplicável.
- Reprodução de áudio/vídeo com Media3 e serviço de sessão multimédia.
- Renderização de páginas PDF com o PdfRenderer do sistema.
- Fluxo de compilação automática no GitHub Actions.

## Requisitos de desenvolvimento

- JDK 17
- Android SDK Platform 36
- Gradle 8.13
- Android Studio recente

Abra o repositório no Android Studio e sincronize o Gradle. O workflow GitHub Actions compila o APK de depuração e publica-o como artefacto quando o build passar.

## Privacidade e segurança

Files Media pede apenas permissões necessárias para descobrir e abrir conteúdo multimédia. Para outros locais de armazenamento, utiliza o seletor de pastas do Android, que permite ao utilizador escolher explicitamente a pasta a que a aplicação terá acesso.

As ações de apagar, renomear e mover podem depender do fornecedor de documentos, da versão do Android e da autorização concedida. Não prometa acesso a todas as pastas protegidas do sistema.

## Sobre imagens e testes de multimédia

Os ficheiros pessoais usados para testar a aplicação permanecem no dispositivo de quem testa. Não incluir episódios, imagens ou outros conteúdos de Naruto (ou de outras obras protegidas) nos recursos distribuídos do aplicativo. Os testes podem usar ficheiros locais que o utilizador tem direito de reproduzir.

## Como ajudar

Relata problemas com:
- Modelo e versão do Android.
- Tipo de ficheiro e origem (MediaStore ou pasta selecionada).
- Passos para reproduzir o problema.
- Mensagem de erro ou captura de ecrã, sem incluir informação privada.

O objetivo é tornar o Files Media útil, rápido, acessível e confiável para o maior número possível de pessoas.
