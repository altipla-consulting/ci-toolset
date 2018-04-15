
import groovy.json.JsonOutput
import java.nio.file.Paths
import altipla.builder.KubeContainer
import altipla.builder.DockerVolume
import altipla.builder.DockerEnv


class builder implements Serializable {
  private String project = null, zone = null, cluster = null
  private def script = null, env = null
  private gcloudInstalled = false

  def configDefault(buildsToKeep='10') {
    [
      [
        $class: 'BuildDiscarderProperty',
        strategy: [
          $class: 'LogRotator',
          numToKeepStr: buildsToKeep,
        ],
      ],
    ]
  }

  def configGithubOnCommit(projectUrl, buildsToKeep='10') {
    [
      [
        $class: 'BuildDiscarderProperty',
        strategy: [
          $class: 'LogRotator',
          numToKeepStr: buildsToKeep,
        ],
      ],
      [
        $class: 'GithubProjectProperty',
        projectUrlStr: projectUrl,
      ],
      [
        $class: 'PipelineTriggersJobProperty',
        triggers: [
          [
            $class: 'GitHubPushTrigger',
          ],
        ],
      ],
    ]
  }

  def init(script, buildsToKeep='10') {
    initConfig(script, configDefault(buildsToKeep))
  }

  def initConfig(script, props) {
    this.@script = script
    this.env = script.env

    this.script.properties(props)
  }

  def initGerrit(script, gerritProject, onMerge=false, buildsToKeep='10') {
    this.@script = script
    this.env = script.env

    def props = [
      [
        $class: 'BuildDiscarderProperty',
        strategy: [
          $class: 'LogRotator',
          numToKeepStr: buildsToKeep,
        ],
      ],
    ]
    if (gerritProject) {
      def events = [
        [$class: 'PluginPatchsetCreatedEvent'],
        [$class: 'PluginDraftPublishedEvent'],
      ]
      if (onMerge) {
        events = [
          [$class: 'PluginChangeMergedEvent'],
        ]
      }
      props += [
        [
          $class: 'PipelineTriggersJobProperty',
          triggers: [
            [
              $class: 'GerritTrigger',
              serverName: 'Gerrit Altipla',
              customUrl: "${this.env.BUILD_URL}console",
              gerritProjects: [
                [
                  $class: 'GerritProject',
                  compareType: 'PLAIN',
                  pattern: gerritProject,
                  branches: [
                    [
                      $class: 'Branch',
                      compareType: 'PLAIN',
                      pattern: 'master',
                    ],
                  ],
                ],
              ],
              triggerOnEvents: events,
            ],
          ],
        ],
      ]
    }
    this.script.properties(props)
  }

  def setProject(value) {
    this.@project = value
  }

  def setZone(value) {
    this.@zone = value
  }

  def setCluster(value) {
    this.@cluster = value
  }

  def getDefaultTag() {
    "${new Date().format('yyyyMMdd')}.${env.BUILD_ID}"
  }

  def installGcloud() {
    if (gcloudInstalled) {
      return;
    }
    gcloudInstalled = true;

    this.env.PATH = "/root/google-cloud-sdk/bin:${this.env.PATH}"

    this.script.sh "gcloud config set core/project ${project}"
    this.script.sh "gcloud config set container/cluster ${cluster}"
    this.script.sh "gcloud config set compute/zone ${zone}"
    this.script.sh "gcloud config set core/disable_prompts True"
    if (cluster) {
      this.script.sh "gcloud container clusters get-credentials ${cluster}"
    }
  }

  def docker(image, dockerfile='Dockerfile', context='.') {
    this.script.docker.build("${project}/${image}", ["-f", "${context}/${dockerfile}", context].join(' '))
  }

  def registry(container, tag='') {
    tag = tag ?: defaultTag

    this.script.docker.withRegistry('https://eu.gcr.io', '35e93828-31ad-45fd-90a3-21a3c9dcf332') {
      container.push(tag)
      container.push('latest')
    }
  }

  def gitTag(tag='') {
    tag = tag ?: defaultTag

    this.script.sshagent(['c983ed20-1b6c-41c0-b3c4-8411d7f8c482']) {
      this.script.sh "git tag -f ${tag}"
      this.script.sh "git push --force origin refs/tags/${tag}:refs/tags/${tag}"
    }
  }

  def kubernetes(deployment, image='', containers=null, tag='') {
    installGcloud()
    
    tag = tag ?: defaultTag
    if (!image) {
      image = deployment
    }
    if (!containers) {
      containers = [new KubeContainer(name: deployment)]
    }

    def patchContainers = []
    for (KubeContainer container : containers) {
      if (!container.image) {
        container.image = image
      }
      if (!container.tag) {
        container.tag = tag
      }

      patchContainers.add([
        name: container.name,
        image: "eu.gcr.io/${project}/${container.image}:${container.tag}",
        env: [
          [name: 'VERSION', value: container.tag],
        ],
      ])
    }
    def patch = [spec: [template: [spec: [containers: patchContainers]]]]
    this.script.sh "kubectl patch deployment ${deployment} --patch '${JsonOutput.toJson(patch)}'"
  }

  def dockerShell(image, command, volumes=null, environment=null) {
    if (!volumes) {
      volumes = []
    }

    def args = []
    for (DockerVolume volume : volumes) {
      if (volume.source.startsWith('./')) {
        volume.source = Paths.get(this.env.WORKSPACE, volume.source.substring(2))
      } else if (volume.source == '.') {
        volume.source = this.env.WORKSPACE
      }
      
      args.add("-v ${volume.source}:${volume.dest}")
    }
    for (DockerEnv env : environment) {
      args.add("-e ${env.name}=\"${env.value}\"")
    }
    command = command.replaceAll('"', '\\"');
    this.script.sh "docker run -t ${args.join(' ')} ${project}/${image} bash -c \"${command}\""
  }

  def gsutil(command) {
    installGcloud()
    this.script.sh "gsutil ${command}"
  }

  def gcloud(command) {
    installGcloud()
    this.script.sh "gcloud ${command}"
  }
}
