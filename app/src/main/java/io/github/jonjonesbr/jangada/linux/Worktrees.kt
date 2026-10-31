package io.github.jonjonesbr.jangada.linux

/**
 * Trabalhos isolados (git worktree) de um repositório de ~/projetos: uma cópia de trabalho
 * numa branch própria (jangada/<nome>). Quem chama [create] roda fora da thread principal:
 * ele roda o git e espera o processo terminar.
 */
object Worktrees {

    /** Resultado de [create]: a pasta nova, ou a saída do git quando não deu certo. */
    sealed class Result {
        class Created(val folder: String) : Result()
        class Failed(val output: String) : Result()
    }

    /** Repositório que pode ser a base de um trabalho isolado: pasta git que não é worktree. */
    fun canBeBase(item: Workspace.Folder): Boolean = item.branch != null && item.worktreeOf == null

    /**
     * Pode ser a base de um trabalho novo: [canBeBase] e o nome do repositório cabe em
     * "<repo>--<nome>" (pasta válida). Só confere textos: pode rodar na thread principal.
     */
    fun canStart(item: Workspace.Folder): Boolean =
        canBeBase(item) && Workspace.worktreeFolder(item.name, "x") != null

    /**
     * Cria o trabalho [name] do repositório [repo] (`git worktree add -b jangada/<name> <pasta>`).
     * Fora da thread principal: espera o git terminar. Um nome que não vira pasta válida dá
     * Failed sem saída; quem chama confere antes com [Workspace.worktreeFolder].
     */
    fun create(linux: LinuxEnv, repo: String, name: String): Result {
        val folder = Workspace.worktreeFolder(repo, name) ?: return Result.Failed("")
        val made = Git.run(linux, Workspace.path(repo), "worktree", "add", "-b", "jangada/$name", Workspace.path(folder))
        return if (made.code == 0) Result.Created(folder) else Result.Failed(made.shown)
    }
}
