package com.zenvia.komposer.junit

import com.zenvia.komposer.model.Komposition
import com.zenvia.komposer.runner.KomposerRunner
import org.junit.rules.ExternalResource

/**
 * @author Tiago de Oliveira
 * */
class KomposerRule extends ExternalResource {

    private final KomposerRunner runner
    private final String composeFile
    private Map<String, Komposition> runningServices
    private final dockerCfg = null
    private final pull = true
    private final privateNetwork = false
    private final forcePull = false
    private final maxAttempts = 5

    def KomposerRule(String compose, Boolean pull = true) {
        this.runner = new KomposerRunner()
        this.composeFile = compose
        this.pull = pull
    }

    def KomposerRule(String compose, KomposerRunner runner) {
        this.runner = runner
        this.composeFile = composeFile
    }

    def KomposerRule(options = []) {

        def defaultOptions = [
            compose:        null,
            dockerCfg:      null,
            pull:           true,
            privateNetwork: false,
            forcePull:      false,
            maxAttempts:    5
        ]

        options = defaultOptions << options;

        this.composeFile = options.compose;
        this.dockerCfg = options.dockerCfg;
        this.pull = options.pull;
        this.privateNetwork = options.privateNetwork;
        this.forcePull = options.forcePull;
        this.maxAttempts = options.maxAttempts;

        this.runner = new KomposerRunner(this.dockerCfg, this.privateNetwork)
    }

    @Override
    void before() throws Throwable {
        this.runningServices = this.runner.up(this.composeFile, this.maxAttempts, this.pull, this.forcePull)
    }

    @Override
    void after() {
        this.runner.down(this.runningServices)
        this.runner.rm(this.runningServices)
        this.runner.finish()
    }

    def Map<String, Komposition> getContainers() {
        return this.runningServices
    }

    def stop(String serviceName) {
        def containerId = runningServices[serviceName].containerId
        def containerInfo = this.runner.stop(containerId)
        this.runningServices[serviceName].containerInfo = containerInfo
    }

    def start(String serviceName) {
        def containerId = runningServices[serviceName].containerId
        def containerInfo = this.runner.start(containerId)
        this.runningServices[serviceName].containerInfo = containerInfo
    }

    def URI getHostURI() {
        return this.runner.getHostUri()
    }
}
