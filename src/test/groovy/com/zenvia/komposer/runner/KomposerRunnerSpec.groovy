package com.zenvia.komposer.runner

import com.spotify.docker.client.DefaultDockerClient
import com.spotify.docker.client.LogStream
import com.spotify.docker.client.messages.ContainerCreation
import com.spotify.docker.client.messages.ContainerInfo
import spock.lang.Specification

/**
 * @author Tiago de Oliveira
 * */
class KomposerRunnerSpec extends Specification {

    DefaultDockerClient dockerClient = Mock(constructorArgs: [DefaultDockerClient.fromEnv()])
    def runner = new KomposerRunner(dockerClient)
    def services = ['sender': [containerId: '9998877', containerName: 'komposer_resources_sender_']]
    def maxAttempts = 5

    def setup() {
        runner.host = 'http://teste:5656'
    }

    def "up"() {
        given:
            def file = 'src/test/resources/docker-compose.yml'
            def creation = new ContainerCreation()
            creation.id = '9998877'
            def info = new ContainerInfo()
            def stream = Mock(LogStream)
        when:
            dockerClient.createContainer(_, _) >> creation
            dockerClient.inspectContainer(creation.id) >> info
            dockerClient.logs(_, _) >> stream
            def result = runner.up(file, maxAttempts)
        then:
            result
            result.sender.containerId == services.sender.containerId
            result.sender.containerName.contains(services.sender.containerName)
            result.sender.containerInfo == info
    }

    def "up with force pull"() {
        given:
            def file = 'src/test/resources/docker-compose.yml'
            def creation = new ContainerCreation()
            creation.id = '9998877'
            def info = new ContainerInfo()
            def stream = Mock(LogStream)
        when:
            dockerClient.createContainer(_, _) >> creation
            dockerClient.inspectContainer(creation.id) >> info
            dockerClient.logs(_, _) >> stream
            def result = runner.up(file, maxAttempts, true, true)
        then:
            result
            result.sender.containerId == services.sender.containerId
            result.sender.containerName.contains(services.sender.containerName)
            result.sender.containerInfo == info
    }

    def "down"() {
        when:
            runner.down(services)
        then:
            dockerClient.killContainer('9998877')
    }

    def "rm"() {
        when:
            runner.rm(services)
        then:
            dockerClient.removeContainer('9998877')
    }

    def "getHostURI"() {
        when:
            URI hostUri = runner.getHostUri()

        then:
            hostUri.getHost() == "teste"
            hostUri.getPort() == 5656
            hostUri.getScheme() == "http"
    }
}
