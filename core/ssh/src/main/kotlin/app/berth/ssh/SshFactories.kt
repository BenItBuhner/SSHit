package app.berth.ssh

import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.common.Factory
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyFormat

internal object SshFactories {
    private val config: DefaultConfig by lazy { DefaultConfig() }

    fun fileKeyProvider(format: KeyFormat): FileKeyProvider =
        Factory.Named.Util.create(config.fileKeyProviderFactories, format.toString())
            ?: throw IllegalArgumentException("no key provider for format $format")
}
