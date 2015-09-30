package com.zenvia.komposer.builder

import com.spotify.docker.client.AnsiProgressHandler
import com.spotify.docker.client.DockerClient
import com.spotify.docker.client.DockerException
import com.spotify.docker.client.ProgressHandler
import com.spotify.docker.client.messages.*
import groovy.util.logging.Slf4j
import org.yaml.snakeyaml.Yaml

import java.nio.file.Paths
import java.security.SecureRandom

/**
 * @author Tiago Oliveira
 * */
@Slf4j
class KomposerBuilder {

    private final File composeFile
    private final DockerClient client
    private final hubLogin

    def KomposerBuilder(DockerClient client, String hubUser, String hubPass, String hubMail) {
        this.composeFile = composeFile
        this.client = client
        this.hubLogin = [user: hubUser, pass: hubPass, mail: hubMail]
    }

    def KomposerBuilder(DockerClient client) {
        this.composeFile = composeFile
        this.client = client
    }

    def build(File composeFile, Integer maxAttempts, pull = true, forcePull = false) {
        log.info("Building containers from ${composeFile.absolutePath}")

        def prefix = Paths.get(composeFile.absolutePath).parent.fileName
        def instanceId = new SecureRandom().nextInt(1000)
        def namePattern = "komposer_${prefix}_%s_${instanceId}"

        def compose = new Yaml().load(composeFile.text)
        def result = [:]

        compose.reverseEach {
            String service = it.key

            log.info "Processing service: $service"

            def containerConfig = this.createContainerConfig(it.value, service, namePattern, maxAttempts, pull, forcePull)
            def hostConfig = this.createHostConfig(it.value, namePattern)
            def containerName = sprintf(namePattern, [service])

            result[service] = [name: containerName, container: containerConfig, host: hostConfig]
        }

        return result
    }

    def ContainerConfig createContainerConfig(service, serviceName, namePattern, Integer maxAttempts, pull = true, Boolean forcePull = false) {
        log.info("Creating container config for [${serviceName}], pullImage = ${pull}")

        def builder = ContainerConfig.builder()

        def imageName = service.image
        if (imageName && pull) {
            this.pullImage(imageName, maxAttempts, forcePull)
        } else if (service.build) {
            imageName = this.buildImage(service.build, serviceName, namePattern)
        }

        def exposed = []
        service.expose?.each {
            def port = it
            if (!port.contains("/")) {
                port = "$port/tcp"
            }
            exposed += [port]
        }

        service.ports?.each { String iPort ->
            def port = iPort.split(':')[0]
            if (!port.contains("/")) {
                port = "$port/tcp"
            }
            exposed += [port]
        }

        def envs = []
        service.environment?.each {
            envs += [it]
        }

        def volumes = []
        service.volumes?.each {
            volumes += [it]
        }

        builder.image(imageName)

        if (service.command) {
            builder.cmd(service.command.split(' '))
        }

        if (service.hostname) {
            builder.hostname(service.hostname)
        }

        if (service.domainname) {
            builder.domainname(service.domainname)
        }

        builder.exposedPorts(exposed.toSet())
        builder.env(envs)
        builder.volumes(volumes.toSet())

        return builder.build()
    }

    def createHostConfig(service, namePattern) {
        log.info("Creating host config...")
        def builder = HostConfig.builder()

        def ports = [:]
        service.ports?.each { String mapping ->
            log.info("Processing $mapping")

            def externalPort = ''
            def internalPort = ''
            def host = '0.0.0.0'

            def separatorCount = (mapping =~ ':').count
            if (separatorCount > 0) {
                switch (separatorCount) {
                    case 1:
                        externalPort = mapping.split(':')[0]
                        internalPort = mapping.split(':')[1]
                        break
                    case 2:
                        host = mapping.split(':')[0]
                        externalPort = mapping.split(':')[1]
                        internalPort = mapping.split(':')[2]
                        break
                }
            } else {
                internalPort = mapping
            }

            if (internalPort && !internalPort.contains('/')) {
                internalPort += '/tcp'
            }

            if (externalPort && !externalPort.contains('/')) {
                externalPort += '/tcp'
            }

            log.info("Port mapping $internalPort -> $externalPort:$host")
            ports[internalPort] = [PortBinding.of(host, externalPort)]
        }

        def links = []
        service.links?.each { String link ->
            log.info("Processing $link")

            def linkAlias
            def linkContainer

            if (link.contains(':')) {
                linkAlias = link.split(':')[1]
                linkContainer = link.split(':')[0]
            } else {
                linkAlias = link
                linkContainer = linkAlias
            }

            linkContainer = sprintf(namePattern, [linkContainer])
            def linkUrl = linkContainer + ':' + linkAlias

            log.info("Creating link $linkUrl ")
            links += [linkUrl]
        }

        builder.portBindings(ports)
        builder.links(links)
        if (service.net) {
            builder.networkMode(service.net)
        }
        builder.networkMode()
        return builder.build()
    }

    def pullImage(String image, Integer maxAttempts, Boolean forcePull = false) {
        if (!image.contains(':')) {
            image += ':latest'
        }

        def progress = new ProgressHandler() {
            @Override
            void progress(ProgressMessage message) throws DockerException {

                if (message.error()) {
                    throw new DockerException(message.error())
                }

                if (message.progressDetail() != null) {
                    final String ID = message.id()

                    String progress = message.progress()
                    progress ?: ""

                    log.info(sprintf("%s: %s %s%n", [ID, message.status(), progress]))
                } else {

                    String value = message.stream()
                    if (value) {
                        value = value.trim()
                    } else {
                        value = message.status()
                    }

                    value ?: message.toString()

                    log.info(value)
                }
            }
        }

        def hasImageLocal = this.client.listImages().findAll { it.repoTags.toString().contains(image) }
        if (!hasImageLocal || forcePull) {
            pull(image, progress, maxAttempts)
        }
    }

    def pull(String image, ProgressHandler progress, Integer maxAttempts) {
        Integer attempts = 1;
        Exception lastException = null

        while(attempts <= maxAttempts) {
            try {
                if (this.hubLogin && this.hubLogin.user) {
                    log.info("Pulling image [${image}] using authentication...")
                    AuthConfig auth = AuthConfig.builder().username(this.hubLogin.user).password(this.hubLogin.pass).email(this.hubLogin.mail).build()
                    this.client.pull(image, auth, progress)
                } else {
                    log.info("Pulling image [${image}] without auth...")
                    this.client.pull(image, progress)
                }

                return
            } catch (Exception e) {
                lastException = e
                def message = "Image cannot be pulled from repository. Attempt [${attempts}] of [${maxAttempts}]"
                log.warn(message)
                attempts++
                Thread.sleep(3000)
            }
        }

        def message = "Impossible to pull the image from repository, please do it manually"
        log.error(message, lastException)
        throw new DockerException(message, lastException)
    }

    def buildImage(path, serviceName, namePatterm) {
        log.info("Building image on [${path}]")
        def imageName = sprintf(namePatterm, [serviceName])
        def ph = new AnsiProgressHandler()
        this.client.build(Paths.get(path), ph)
        return imageName
    }
}
