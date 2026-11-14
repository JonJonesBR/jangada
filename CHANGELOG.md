# Novidades

## 1.21.0

- Chat: chip do modo de permissão no fim da barra de atalhos, com nomes simples e troca na hora (agy, Claude e demais agentes com modos).
- Chat: pedido de permissão mais simples, um cartão enxuto com Detalhes; a faixa de aviso só aparece quando o cartão sai da tela.
- Antigravity CLI (agy): caminho legível no pedido de permissão, nomes das ferramentas traduzidos e sem mensagem duplicada.
- Chat ACP: o pedido de permissão de um agente que dormiu (ou foi parado) e voltou não some mais; antes o chat parecia travado, com o agente esperando uma resposta que não aparecia.
- Teste do emulador: o Claude falso grava o arquivo da conversa, para cobrir a retomada com --resume.

## 1.20.0

- Antigravity CLI (agy): agora tem chat, pelo servidor ACP que vem no próprio CLI (`agy acp`); o primeiro login abre o terminal para colar o código da conta Google.
- Chat do OpenCode: cada subagente aparece num cartão com agente e descrição, estado com o tempo, ações recolhidas e o resultado ao terminar; a faixa de status resume quantos subagentes há e quantos rodam. O pedido de permissão de um subagente mostra quem pediu, e o cartão indica que espera a sua resposta.
- Chat: faixa de status honesta, com relógio do turno e do tempo de cada ação do agente; um único pedido de permissão por vez, com o risco e fila para os seguintes.
- Chat: /clear e /reset aparecem nas sugestões de comandos.
- Conversas: apagar todas (no chat e no painel do projeto) e apagar uma pelo painel; uma conversa sem arquivo não é mais retomada (isso causava o erro código 1).
- Agentes: os comandos rodados pelo app não usam paginador nem pedem resposta interativa.
- Mais testes no emulador (exportar conversa, encerrar trabalho isolado, Mudanças, limites, Consumo do OpenCode, tela inicial e terminal).

## 1.19.0

- Projetos: cada projeto mostra quanto ocupa e, no painel, o total separado em git e dependências. O painel também lista as conversas do Claude do projeto (tocar abre a conversa) e apaga as paradas há mais de 30 dias, só com o projeto fechado.
- Projetos: Apagar dependências (node_modules, .venv e parecidos), com a lista das pastas antes de confirmar; nunca entra no .git nem segue links.
- Chat: Buscar nas conversas (Histórico do Claude), com o trecho onde a palavra aparece; Exportar conversa em Markdown, no arquivo que você escolher; os anexos ainda não enviados ficam no rascunho do trabalho e voltam ao reabrir.
- Chat: encerrar um trabalho isolado trava a pasta até o git terminar, para nenhum chat reabrir nela no meio, e não fica esperando para sempre um agente que não fecha.
- OpenCode, Contas e chaves: a lista vem do próprio opencode (sem precisar do sqlite3); antes de conectar, o app pergunta ao provedor se a chave vale, sem gastar nada, e oferece digitar de novo quando ela é recusada.
- OpenCode: Consumo nos Ajustes do chat (tokens e custo por modelo nos últimos 7 dias); limites do OpenCode Go (5 horas, semanal e mensal) no chat e no cartão, com aviso a 90%; e, quando o limite grátis acaba, um aviso com o que fazer e o link do OpenCode Go.
- OpenCode: nenhum comando do app deixa o servidor do opencode rodando em segundo plano.
- Clone e cópia: o progresso volta à tela quando você retorna (a não ser que tenha mandado para segundo plano), e o aviso de falha abre o log.
- Testes no emulador para o painel do projeto, a busca e o rascunho com anexos.

## 1.18.0

- Projetos: tela nova, aberta pelo cartão de armazenamento da tela inicial, com a lista de ~/projetos e, para cada um, Renomear, Duplicar e Excluir. Antes de renomear ou excluir, os chats e terminais da pasta são fechados, e nenhum chat abre nela até o fim.
- Renomear leva junto as conversas do Claude (o continuar segue funcionando), os rascunhos e a compactação de cada trabalho; as conversas de outros agentes ficam no nome antigo.
- Excluir pede o nome do projeto digitado e mostra os arquivos sem commit; um trabalho isolado sai pelo git. Apagar as conversas do Claude é opcional. Um repositório com trabalhos isolados não se renomeia nem se exclui antes deles.
- Duplicar copia a pasta inteira (também o que está fora do git, sem seguir links e sem os trabalhos isolados) em segundo plano, com Cancelar, e confere o espaço livre antes.
- Mudanças: Atualizar do remoto (git pull só avançando a branch), com explicação quando as branches se separaram ou há mudanças sem commit no caminho.

## 1.17.0

- Chat do Claude: perguntas do agente (AskUserQuestion) viram um cartão com as opções, uma por pergunta, com "Outra resposta…" para escrever; o plano (ExitPlanMode) aparece formatado, com Aprovar plano ou Continuar planejando, e o aviso não oferece mais "Permitir" para ele.
- Chat: um segundo Parar encerra o agente que não responde ao primeiro. Agente ACP que não abre a conversa em 5 minutos avisa, em vez de ficar subindo para sempre. Uma mensagem estranha do agente não trava o turno nem deixa um pedido sem resposta.
- Chat: anexos e o que foi compartilhado não somem nem se duplicam quando a tela é recriada (girar, trocar o tema). Histórico com o agente ACP dormindo avisa em vez de falhar; copiar que falha avisa.
- OpenCode, Contas e chaves: a lista é lida no celular mesmo com o banco ocupado, e uma consulta que falha não aparece como "nenhuma conta"; o login é conferido antes de dizer que deu certo; conta sem rótulo se remove pelo terminal.
- Git e clone: leituras do git (status, diff, log) têm tempo limite de 2 minutos, com aviso próprio; endereço de repositório com senha ou token é recusado.
- Serviço: aba de terminal encerrada não segura mais o aviso do serviço; backup roda em primeiro plano e o aviso diz que está em andamento. A limpeza automática espera terminais e agentes pararem, e uma busca que falha não apaga nada.
- Telas: a barra de cima ficou legível no tema claro, e trocar claro/escuro no Android redesenha as telas abertas.
- Linux também em x86_64 (Chromebooks e emuladores).
- Reforços: extração da imagem do Linux mais segura, restaurar backup nunca mexe em ~/celular, títulos e blocos de código do Markdown corrigidos.
- Testes automáticos num emulador (telas, instalação do Linux, chats do Claude e do OpenCode, contas, serviço), com agentes falsos e sem nenhum dado pessoal.

## 1.16.0

- Espaço no Linux: o painel ganhou uma barra com Linux e agentes, conversas, projetos e o que pode limpar, uma lista das maiores pastas (a partir de 50 MB) e o espaço livre do celular. As legendas ficaram curtas.
- Liberar espaço: também apaga o que está parado há mais de 7 dias em /tmp (entradas inteiras sem nenhum arquivo, socket ou pasta mexido nesse prazo; nunca /tmp/claude-0 em si, .X11 e .ICE). As saídas de tarefas antigas do Claude Code chegaram a ocupar 17 GB.
- Limpeza automática: no máximo uma vez por dia, com o Linux instalado e nada instalando, atualizando ou limpando. Dá para desligar nos Ajustes ("Limpar sozinho uma vez por dia").
- Liberar espaço nos Ajustes ficou muito mais rápido: o Debian não é mais varrido três vezes; o próprio script informa quanto liberou.
- Chat do OpenCode: painel Contas e chaves (adicionar chave, remover, voltar ao plano free).
- Chat ACP: reabrir uma conversa que já está na tela não refaz nem duplica o histórico, e uma faixa avisa "Recarregando conversa…" enquanto o agente recarrega a sessão.

## 1.15.0

- Limites do plano: quando o limite de 5 horas ou o semanal passa de 90%, chega um aviso (uma vez por período). O cartão do Claude e o do Codex na tela inicial mostram o uso, em vermelho a partir de 80%.
- Chat: trocar modelo, esforço, compactação automática, conversa ou começar outra espera o agente terminar a resposta e as tarefas em segundo plano, em vez de cortá-las.
- Chat: o agente que fecha no meio da resposta avisa uma vez só (também nos agentes ACP) e oferece Continuar; o Continuar não aparece numa conversa que você já continuou. Agente ACP que não responde ao iniciar avisa em 2 minutos.
- Chat ACP: uma opção de permissão de tipo desconhecido aparece com o nome dado pelo agente, por último, e não vira mais "Permitir".
- Memória: servidores que o agente solta (nohup, setsid) continuam contando como dele, e o trabalho não dorme com eles rodando. A linha de cada trabalho não mostra mais "0 processo".
- Liberar espaço: roda em primeiro plano, nunca duas ao mesmo tempo, e o aviso do serviço diz que está limpando. O painel de espaço conta as conversas do Hermes e do oh-my-pi.
- Correções: uma pasta ~/celular com arquivos é guardada ao lado (celular.antigo-<data>) em vez de apagada; fotos giram pela orientação também no Android 8 e 9; desinstalar nunca apaga arquivos de projeto; nomes de worktree que o git recusa são recusados antes; a faixa de segundo plano não fica com texto vazio.
- Painéis: ação Fechar pelo leitor de tela, e o título de uma linha não é mais espremido pelo valor à direita.
- Capturas de tela novas no README.

## 1.14.0

- Chat: compactação automática configurável, nos Ajustes (geral) e em cada trabalho (menu ⋮ ou chip de contexto): padrão do agente, desligada (no Claude) ou ao chegar a 50, 60, 70, 80 ou 90% do contexto. No Claude, quem compacta é o próprio Claude Code; nos outros agentes, o app manda /compact ao fim da resposta.
- Liberar espaço: uma limpeza só, na tela inicial e nos Ajustes, que apaga apenas o que os programas baixam de novo sozinhos (caches de download, temporários de navegador e versões antigas do Claude Code, mantendo a atual, a mais nova e as em uso). Não apaga mais navegadores de teste nem pacotes do npx. O painel de espaço mostra quanto dá para liberar.
- Painéis: puxar para baixo pela alça, título ou texto fecha o painel.
- Espaço: a linha de limpar diz por que está desligada (nada a limpar, ou instalação/atualização em andamento).

## 1.13.0

- Tela inicial: com chats abertos, uma barra mostra a memória do celular dividida entre agentes, outros apps e livre, e quanto dá para usar antes de o Android começar a fechar apps. Cada trabalho mostra quanto usa (≈ 420 MB · 6 processos) ou que está dormindo; o painel ⇄ do chat também.
- Tela inicial: o app guarda até onde cada agente costuma chegar de memória e diz se cabe mais um, quanto pôr os parados para dormir libera ou que não cabe. O painel ao abrir mais um agente também usa essa conta.
- Chat: no Claude e no Codex, um chip mostra quanto dos limites de 5 horas e semanal do plano já foi usado; tocar nele mostra quando cada um renova. Os outros agentes não informam limites.
- Tela inicial: o cartão de armazenamento mostra o espaço livre do celular e, em "Ver o que ocupa espaço", o Linux dividido em projetos, conversas dos agentes, caches e o resto, com Limpar caches (não apaga conversas, logins nem projetos).
- Correção: tocar em Parar não mostra mais o cartão "O agente encontrou um erro" (no Claude e nos outros agentes).

## 1.12.1

- Chat: com o teclado aberto, a conversa ganha espaço. Os atalhos acima da caixa somem, a faixa de segundo plano fica em uma linha (tocar nela continua abrindo o painel para parar), a caixa de texto vai até 3 linhas (o resto rola dentro dela) e o título mostra só a pasta e o branch. Ao fechar o teclado, tudo volta.
- Chat: o botão ↓ ficou menor e semitransparente, para tapar menos a conversa; a área de toque continua do mesmo tamanho.
- Correção: o botão ↓ vai sempre até o fim da conversa. Antes, com respostas mais altas que a tela, parava em pontos do meio, e tocar de novo subia ou descia a lista.

## 1.12.0

- Chat: subagentes e comandos que o Claude deixa rodando em segundo plano aparecem na faixa acima da caixa de texto, com o que cada um faz agora, mesmo depois do fim da resposta. Com eles, o trabalho conta como Trabalhando e não dorme para liberar memória.
- Chat: tocar na faixa abre um painel para parar uma tarefa em segundo plano ou todas. O Parar de sempre continua parando só a resposta do agente principal, e as mensagens seguem indo para ele.
- Chat: quando o Claude volta a trabalhar sozinho (um subagente terminou), aparece Trabalhando… e chega o aviso de resposta pronta.
- Chat: enviar uma mensagem leva a conversa até ela, e a resposta aparece logo abaixo. Lendo mais para cima, o botão ↓ volta à última mensagem.

## 1.11.1

- Correção: o toque na notificação "Linux rodando" volta para a tela em que você estava; com o app fechado, abre o chat mais recente (com a tela inicial embaixo) ou a tela inicial. Antes abria sempre o terminal e, só com chats abertos, criava um terminal novo.

## 1.11.0

- Tela inicial: os agentes instalados aparecem primeiro na lista, e os outros vêm depois.
- Tela inicial: Atualizar todos, ao lado do título Agentes, atualiza de uma vez os agentes que pedem atualização, um por vez.
- Agentes: instalar, atualizar ou desinstalar com outra tarefa rodando entra na fila (instalações ao mesmo tempo disputam o apt e a memória). O cartão mostra "Na fila para instalar/atualizar/desinstalar" e Cancelar tira da fila; ao cancelar a tarefa em andamento, Cancelar todos para a fila inteira.
- Avisos: com a fila, um aviso só quando ela termina, mostrando a falha se houve uma.
- Correção: agentes sem versão no npm (Hermes, Antigravity) não voltam mais a pedir Atualizar quando a atualização termina com a tela inicial fechada.

## 1.10.0

- Memória: os comandos que os agentes rodam (navegador de testes, builds, servidores) cedem primeiro quando a memória acaba. O Android encerra o comando, que falha com "Killed", e o agente continua.
- Memória: agentes parados dormem para liberar memória (memória apertada, tempo parado escolhido nos Ajustes ou para abrir outro agente) e aparecem como Dormindo; a próxima mensagem os acorda na mesma conversa. Quem trabalha, espera você ou tem comandos rodando não dorme.
- Memória: Agentes ao mesmo tempo, nos Ajustes: automático pela RAM do celular (até 4) ou de 1 a 4. Aviso de pouca memória com o trabalho que mais usa, e um painel ao abrir mais um agente com a memória apertada.
- Memória: o app orienta os agentes (Claude, Codex, OpenCode, Qwen, Pi e outros) a rodar um navegador de testes por vez e fechá-lo ao terminar, nas instruções globais de cada um. Desliga nos Ajustes.
- Chat: o erro 137 explica que o Android encerrou o agente por falta de memória e que dá para continuar a conversa.
- Chat: ao digitar "/", sugestões começando por /new, /resume e /compact (o app executa na hora), seguidas dos comandos do agente, agora também no Claude. /new, /clear e /reset digitados abrem a Nova conversa; /resume abre o Histórico.
- Chat: aviso quando a conversa é compactada, e o "Trabalhando…" não fica mais preso depois do /compact.

## 1.9.0

- Novo projeto: no Novo trabalho da tela inicial e no seletor de pasta do chat, cria uma pasta em ~/projetos com git init e, com login no gh ou no glab, o repositório privado no GitHub ou no GitLab. O chat abre direto nela.
- Clonar um repositório: cole o endereço (https ou ssh) e o app copia o repositório para uma pasta nova, com progresso, Cancelar e Em segundo plano (um aviso chega quando termina). Falhas mostram o registro com uma dica (login, chave ssh, ssh ausente, repositório não encontrado).
- Mudanças: repositório remoto. Vincular um endereço, enviar (git push), trocar o endereço e desvincular; sem remoto, criar no GitHub ou no GitLab (privado ou público), ou entrar e instalar o gh e o glab pelo terminal.
- Chat: o texto ainda não enviado fica como rascunho de cada trabalho e volta ao reabrir o chat.

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
