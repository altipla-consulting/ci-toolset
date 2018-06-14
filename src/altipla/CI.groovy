package altipla

import groovy.json.JsonOutput
import java.nio.file.Paths
import altipla.KubeContainer
import altipla.DockerVolume
import altipla.DockerEnv


class CI implements Serializable {
  String project, zone, cluster
  int buildsToKeep = 10
  
  private def script
  private def params = []
  private def tag = null
  private def gerritProject = ''
  private def gerritOnMerge = false
  
  private def gcloudInstalled = false

  def init(scriptRef) {
    script = scriptRef
    tag = "${new Date().format('yyyyMMdd')}.${script.env.BUILD_ID}"

    def props = [
      [
        $class: 'BuildDiscarderProperty',
        strategy: [
          $class: 'LogRotator',
          numToKeepStr: buildsToKeep.toString(),
        ],
      ],
    ]

    if (params) {
      def jparams = []
      params.each {
        if (it.type == 'string') {
          jparams.add(script.string(name: it.name, description: it.description, defaultValue: it.defaultValue))
        }
        if (it.type == 'boolean') {
          jparams.add(script.booleanParam(name: it.name, description: it.description, defaultValue: it.defaultValue))
        }
      }
      props += [
        script.parameters(jparams)
      ]
    }

    if (gerritProject) {
      def events = [
        [$class: 'PluginPatchsetCreatedEvent'],
        [$class: 'PluginDraftPublishedEvent'],
      ]
      if (gerritOnMerge) {
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
              customUrl: "${script.env.BUILD_URL}console",
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

    script.properties(props)
  }

  def addStringParam(Map param) {
    params.push(type: 'string', name: param.name, description: param.description, defaultValue: param.defaultValue)
  }

  def addBooleanParam(Map param) {
    params.push(type: 'boolean', name: param.name, description: param.description, defaultValue: param.defaultValue)
  }

  def configGerrit(projectName, onMerge=false) {
    gerritProject = projectName
    gerritOnMerge = onMerge
  }

  def container(String name, String context='.', String dockerfile='Dockerfile') {
    def c = script.docker.build "${project}/${name}", "-f ${context}/${dockerfile} ${context}"
    script.docker.withRegistry('https://eu.gcr.io', '35e93828-31ad-45fd-90a3-21a3c9dcf332') {
      c.push tag
      c.push 'latest'
    }

    return c
  }

  def container(Map m) {
    return container(m.name, m.get('context', '.'), m.get('dockerfile', 'Dockerfile'))
  }

  def containerBuildOnly(String name, String context='.', String dockerfile='Dockerfile') {
    return script.docker.build("${project}/${name}", "-f ${context}/${dockerfile} ${context}")
  }

  def _installGcloud() {
    if (gcloudInstalled) {
      return
    }
    gcloudInstalled = true

    script.env.PATH = "/root/google-cloud-sdk/bin:${script.env.PATH}"

    script.sh "gcloud config set core/project ${project}"
    script.sh "gcloud config set core/disable_prompts True"
    if (cluster && zone) {
      script.sh "gcloud config set container/cluster ${cluster}"
      script.sh "gcloud config set compute/zone ${zone}"
      script.sh "gcloud container clusters get-credentials ${cluster}"
    }
  }

  def kubernetes(String deployment, image='', List<KubeContainer> containers=null) {
    _installGcloud()

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

      patchContainers.add([
        name: container.name,
        image: "eu.gcr.io/${project}/${container.image}:${tag}",
        env: [
          [name: 'VERSION', value: tag],
        ],
      ])
    }

    def patch = [spec: [template: [spec: [containers: patchContainers]]]]
    script.sh "kubectl patch deployment ${deployment} --patch '${JsonOutput.toJson(patch)}'"
  }

  def applyConfigMap(configMap) {
    _installGcloud()

    script.sh "kubectl apply -f ${configMap}"
  }

  def runContainer(Map container) {
    def args = []

    if (container.volumes) {
      container.volumes.each {
        if (it.source.startsWith('./')) {
          it.source = Paths.get(script.env.WORKSPACE, it.source.substring(2)).toString()
        } else if (it.source == '.') {
          it.source = script.env.WORKSPACE
        }
        
        args.push "-v ${it.source}:${it.dest}"
      }
    }

    if (container.env) {
      for (DockerEnv env : container.env) {
        args.push "-e ${env.name}=\"${env.value}\""
      }
    }

    container.command = container.command.replaceAll('"', '\\"');
    script.sh "docker run -t ${args.join(' ')} ${container.image} bash -c \"${container.command}\""
  }

  def gsutil(String command) {
    _installGcloud()
    script.sh "gsutil ${command}"
  }

  def gcloud(command) {
    _installGcloud()
    script.sh "gcloud ${command}"
  }

  def gitTag(v='') {
    def version = v ?: tag

    script.sshagent(['c983ed20-1b6c-41c0-b3c4-8411d7f8c482']) {
      script.sh "git tag -f ${version}"
      script.sh "git push --force origin refs/tags/${version}:refs/tags/${version}"
    }
  }

  def buildTag() {
    tag
  }
}
