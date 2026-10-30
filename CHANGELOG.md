# Novidades

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
