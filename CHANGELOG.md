# Novidades

## 1.8.0

- Painel que sobe de baixo, no alcance do polegar, no lugar das listas em diálogo: ajustes do chat, pastas, trabalhos, histórico, ações do resumo, menu do agente e compartilhar. Cada opção tem uma frase explicando e o valor atual.
- Chat: o título mostra o trabalho (agente, pasta e branch) e abre a troca de trabalho; o menu ⋮ tem texto (Nova conversa, Histórico, Trabalhos, Mudanças, Encerrar trabalho isolado, Ajustes do chat).
- Chat: barra de atalhos acima da caixa de texto com a pasta, o modelo e o esforço, a visualização, o contexto (com aviso e Compactar agora a partir de 80%) e o número de mudanças do git.
- Chat: o pedido de permissão fica fixo acima da caixa de texto, com Permitir, Recusar e Detalhes.
- Tela inicial: botão Novo trabalho (agente, pasta e, num repositório, direto ou numa cópia isolada); quem espera você aparece primeiro e em destaque.
- Mudanças: botão para pedir ao agente que revise e faça o commit.
- Acessibilidade: o leitor de tela anuncia resposta pronta, pedidos de permissão e erros com o chat aberto; vibração opcional no fim da resposta; tamanho do texto do chat nas configurações; fechar um trabalho que está rodando pede confirmação.

## 1.7.0

- Trabalhos: cada par agente + pasta é um trabalho, e até 3 rodam ao mesmo tempo sem um parar o outro. O botão ⇄ no chat alterna entre eles; trocar de pasta abre outro trabalho e deixa o anterior rodando. A tela inicial mostra cada trabalho com o estado (Trabalhando, Esperando você, Pronto, Parado) e, com 4 ou mais, filtros por estado e por pasta.
- Trabalho isolado (git worktree): no seletor de pasta, cria uma cópia de trabalho do repositório numa branch própria (jangada/<nome>). Encerrar junta na branch do repositório, descarta (com confirmação) ou mantém.
- Mudanças (git), nos ajustes do chat: arquivos alterados, diff colorido de cada um e commit com mensagem.
- Avisos: o pedido de permissão mostra o que o agente quer fazer e tem os botões Permitir e Recusar (Permitir pede o desbloqueio do celular no Android 12 ou mais novo). Cada trabalho tem o próprio aviso.

## 1.6.0

- Chat: nos ajustes do chat, a linha Pasta escolhe em que repositório de ~/projetos o agente trabalha (ou todos os projetos, como antes), mostrando a branch do git. O título do chat mostra a pasta, e dá para criar uma pasta nova ali.
- Cada pasta tem o próprio histórico de conversas; o terminal aberto a partir do chat (e o agente aberto pela tela inicial) entra na mesma pasta, e o Claude confia nela sem perguntar.
- Trocar de pasta com o agente trabalhando pede confirmação. Os anexos continuam em ~/projetos/anexos, e o Claude os lê de qualquer pasta.

## 1.5.0

- Chat: modos de visualização nos ajustes do chat. Detalhado mostra o raciocínio do agente e o começo da saída de cada ação; Normal fica como antes; Resumo junta as ações de cada resposta numa linha ("9 ações · 5× Comando, 3× Editar"), que abre a lista num toque.
- Chat: mensagens enviadas com o agente ocupado esperam na fila e saem quando ele termina. Parar, ou um erro no turno, descarta a fila; a mensagem não enviada volta para a caixa com um toque.
- Chat: o título mostra quanto do contexto a conversa já usa (Claude e agentes ACP que informam), e um atalho para compactar aparece quando passa de 80%.

## 1.4.0

- Chat: tocar numa ação mostra o resultado dela (saída do comando, arquivo lido, resposta da ferramenta), já limpo e cortado no começo e no fim quando é grande. Ações que falharam mostram a primeira linha do erro na própria conversa. Vale para o Claude, os agentes ACP e as conversas reabertas.
- Terminal: ao abrir um agente, o app espera a confiança na pasta de projetos ser gravada, e o Claude não pergunta de novo.

## 1.3.0

- Tela inicial: cartão de boas-vindas (some ao tocar em Entendi), menos redesenho e versões do npm guardadas por 1 hora.
- Chat: agentes ACP sugerem os comandos / ao digitar; Parar no Claude recusa as permissões pendentes e respeita o cancelamento do agente; "limite de passos" e outros erros com mensagem clara; o histórico reaberto mostra as ações que falharam.
- Acessibilidade: botões do chat e teclas extras com 48 dp, estado do Ctrl/Alt e erros anunciados pelo leitor de tela.
- Segurança: anexos e integração não seguem links nas pastas do Linux; extração de imagens com limites; chave de API ilegível aparece como tal; a saída dos agentes não vai mais para o log do sistema; no máximo 3 links abertos direto a cada 30 s.
- Instalação do Linux: falta de espaço tem a mensagem certa e downloads pela metade são apagados.
- Restaurar um arquivo que não é backup mostra o erro real.
- Terminal: a confiança na pasta do Claude é feita fora da thread principal (sem travar com ~/.claude.json grande).
- Seletor de anexos moderno; células de tabela cortadas ganham reticências; notas dos agentes sem prazo.
- Repositório: release com SHA-256 do APK, CI confere o proot dentro do APK e vigia semanalmente as URLs de instalação; código morto removido e preferências organizadas.

## 1.2.0

- Chat: pedidos de edição mostram o diff (linhas removidas e adicionadas), a lista de tarefas do Claude aparece como plano, e o cartão de permissão mostra sempre o comando, com o pedido inteiro num toque.
- "Sempre permitir" num comando do terminal vale só para aquele comando; o botão diz o que libera.
- Chat dos agentes ACP: falha ao iniciar não trava mais o envio; avisa quando o agente começou conversa nova sem lembrar das anteriores; apagar ou listar conversas com erro mostra o motivo.
- Markdown não apaga mais os "_" de nomes como ANTHROPIC_API_KEY.
- Fotos anexadas chegam ao agente na orientação certa.
- Compartilhar aceita só arquivos vindos de apps (content://), até 20 itens e 100 MB por arquivo.
- Tela inicial: uma falha do Linux na verificação não apaga mais a lista de agentes instalados.
- Terminal: tocar em pontuação não abre um link qualquer.
- Textos e acessibilidade: instalação do Linux avisa que roda em segundo plano, apagar chave de API pede confirmação, e o leitor de tela lê o agente no título do chat.
- Repositório: lint no CI, tempos limite, Dependabot para o Gradle e novos testes.

## 1.1.1

- Desinstalar remove o programa de todos os agentes (Hermes, Qwen Code, Codex, Pi, OpenClaw, oh-my-pi e Antigravity), mantendo configurações e logins.
- Anexos com nome longo mantêm a extensão, e uma cópia que falha no meio é apagada.
- A escolha do backup (tudo ou só projetos) não se perde se a tela for recriada.
- Textos: esforço padrão como "Padrão" e mensagem inicial do chat mais precisa.

## 1.1.0

- Avisos: o celular avisa quando o agente pede permissão ou login, termina a resposta ou dá erro com o chat fora da tela, e quando uma instalação de agente acaba.
- Compartilhar: mande fotos, arquivos e textos de qualquer app direto para o chat de um agente.
- A instalação do Linux continua com a tela apagada ou em outro app, mostra o progresso na notificação e avisa quando termina.
- O Debian usa o DNS da rede do celular (Wi-Fi, dados ou VPN), com DNS públicos de reserva: instalações funcionam em redes que bloqueiam o DNS do Google.
- Capturas de tela e ícone para o README e as lojas; actions do CI atualizadas.

## 1.0.3

- Instalações, atualizações e desinstalações de agentes têm botão Cancelar.
- Reinstalar o Linux e restaurar backup esperam instalações e chat terminarem de verdade antes de apagar ou sobrescrever.
- A notificação fixa só aparece com terminal, chat ou instalação em andamento.
- Chat mais leve em respostas longas; com o Claude Code aberto no terminal, o chat abre uma conversa nova em vez de gravar na mesma.
- Histórico mantém mensagens que começam com "<"; apagar uma conversa fecha o chat antes.
- Erros do chat dizem o que fazer, com a saída técnica em Detalhes.
- Tela inicial redesenha menos durante uma instalação; barras e botões não cortam o texto com fonte grande.
- Repositório: publicação separada do build, Gradle wrapper, actions fixadas, notas da versão em português e inglês, licenças completas.

## 1.0.2

- Chaves de API só para os agentes: instalar, atualizar e desinstalar rodam sem elas no ambiente. Nomes reservados (PATH, HOME...) são recusados.
- Links abertos pelos agentes: só http/https e só arquivos pequenos; backup e restauração nunca deixam o tar pendurado.
- Chat: o texto dos subagentes do Claude não entra mais na resposta; ao parar o agente, permissões pendentes são canceladas; login com Cancelar e tempo limite.
- Chat dos agentes ACP: o adaptador não é instalado duas vezes, o envio espera ele ficar pronto, e a falha tem Tentar de novo e Detalhes.
- Instalação: um download que falha no "curl | bash" agora falha a instalação, e downloads travados são detectados.
- Telas: âmbar com contraste no tema claro, botões de 48dp, ✕ para fechar abas, teclas extras com descrição para leitor de tela, faixa de volta ao chat depois do login, temas do terminal traduzidos.
- Texto de privacidade corrigido.

## 1.0.1

- Chat abre mais rápido: integração com o Linux, conversa mais recente e histórico são lidos fora da thread principal. O terminal não copia mais o proot a cada abertura.
- Chat dos agentes ACP mais estável: ao fechar e reabrir, nada da execução anterior (pedidos, fila, estado) vaza para a nova.
- Mensagens do chat, nomes das ferramentas, avisos da instalação e créditos traduzidos para o inglês.
- Versões fixas do proot (Termux) e da imagem do Debian: toda instalação nova recebe o mesmo sistema.
- Testes automáticos no build (leitor de tar, receitas e scripts, histórico, traduções).

## 1.0.0

Primeira versão pública.

- Linux Debian dentro do app, instalado com um toque.
- Catálogo com 10 agentes: Claude Code, Antigravity CLI (agy), Codex, OpenCode, Pi, oh-my-pi, Qwen Code, Hermes Agent, Command Code e OpenClaw.
- Chat para o Claude Code e para os agentes compatíveis com o Agent Client Protocol, com permissões, anexos, histórico e troca de modelo.
- Terminal com abas, teclas extras, caixa de texto com corretor e voz, temas de cores.
- Login guiado, segundo plano, arquivos do celular, chaves de API com criptografia, backup e limpeza de espaço.
- Modo Simples e Modo Pro, tema claro, escuro ou do sistema.
