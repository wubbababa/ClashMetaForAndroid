package com.github.kr328.clash.service.clash

import android.content.Context
import com.github.kr328.clash.service.util.importedDir
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.UUID

object FirewallProcessor {
    private const val KEY_RULES = "rules"
    private const val KEY_FIND_PROCESS_MODE = "find-process-mode"
    private const val CONFIGURATION_ID = "config.yaml"

    /**
     * Generate a firewall version of the profile.
     *
     * All traffic is captured by TUN, and the rules are rewritten as:
     * - Applications in the whitelist: PROCESS-NAME,<package>,DIRECT
     * - Everything else: MATCH,REJECT
     *
     * Returns the directory containing the modified configuration, used for Clash.load.
     *
     * Any failure will throw an exception to ensure that it never degrades into allowing all traffic.
     */
    fun process(
        context: Context,
        uuid: UUID,
        whitelistPackages: Set<String>,
        selfPackage: String,
    ): File {
        val source = context.importedDir.resolve(uuid.toString())
        val target = context.cacheDir.resolve("firewall").resolve(uuid.toString())

        target.deleteRecursively()
        target.parentFile?.mkdirs()

        source.copyRecursively(target, overwrite = true)

        val configuration = target.resolve(CONFIGURATION_ID)

        if (!configuration.isFile)
            throw IllegalStateException("Profile $CONFIGURATION_ID not found")

        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            indent = 2
            isPrettyFlow = true
        }

        val yaml = Yaml(options)

        val data: MutableMap<String, Any> = configuration.inputStream().use {
            yaml.load(it)
        } ?: throw IllegalStateException("Empty profile $CONFIGURATION_ID")

        val rules = buildList {
            (whitelistPackages + selfPackage).distinct().sorted().forEach {
                add("PROCESS-NAME,$it,DIRECT")
            }
            add("MATCH,REJECT")
        }

        data[KEY_RULES] = rules
        // Ensure process matching works on all connections
        data[KEY_FIND_PROCESS_MODE] = "always"

        OutputStreamWriter(configuration.outputStream(), StandardCharsets.UTF_8).use {
            yaml.dump(data, it)
        }

        return target
    }
}
