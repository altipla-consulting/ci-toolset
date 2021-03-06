package altipla

import groovy.json.JsonOutput

import java.nio.file.Paths

@SuppressWarnings("unused")
class CI implements Serializable {
  String project, zone, cluster
  int buildsToKeep = 10
  
  private def script
  private List params = []
  private def tag = null
  private String _gerritProject = ''
  private boolean _gerritOnMerge = false
  
  private boolean _gcloudConfigured = false

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

    if (_gerritProject) {
      def events = [
        [$class: 'PluginPatchsetCreatedEvent'],
        [$class: 'PluginDraftPublishedEvent'],
      ]
      if (_gerritOnMerge) {
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
              _gerritProjects: [
                [
                  $class: 'GerritProject',
                  compareType: 'PLAIN',
                  pattern: _gerritProject,
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
    _gerritProject = projectName
    _gerritOnMerge = onMerge
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
    return container(m.name as String, m.get('context', '.') as String, m.get('dockerfile', 'Dockerfile') as String)
  }

  def containerBuildOnly(Map m) {
    return containerBuildOnly(m.name as String, m.get('context', '.') as String, m.get('dockerfile', 'Dockerfile') as String)
  }

  def containerBuildOnly(String name, String context='.', String dockerfile='Dockerfile') {
    return script.docker.build("${project}/${name}", "-f ${context}/${dockerfile} ${context}")
  }

  def containerTag(String name, String imageTag, String context='.', String dockerfile='Dockerfile') {
    def c = script.docker.build "${project}/${name}", "-f ${context}/${dockerfile} ${context}"
    script.docker.withRegistry('https://eu.gcr.io', '35e93828-31ad-45fd-90a3-21a3c9dcf332') {
      c.push imageTag
      c.push 'latest'
    }

    return c
  }

  def containerTag(Map m) {
    return containerTag(m.name as String, m.tag as String, m.get('context', '.') as String, m.get('dockerfile', 'Dockerfile') as String)
  }

  def containerAutoTag(String name, String context='.', String dockerfile='Dockerfile') {
    def c = script.docker.build "${project}/${name}", "-f ${context}/${dockerfile} ${context}"
    script.docker.withRegistry('https://eu.gcr.io', '35e93828-31ad-45fd-90a3-21a3c9dcf332') {
      String imageTag = script.sh(script: "docker image inspect ${c.id} -f {{.Id}}", returnStdout: true);
      imageTag = imageTag.trim().split(':')[1].substring(0, 12);

      c.push imageTag
      c.push 'latest'
    }

    return c
  }

  def containerAutoTag(Map m) {
    return containerAutoTag(m.name as String, m.get('context', '.') as String, m.get('dockerfile', 'Dockerfile') as String)
  }

  def configureGoogleCloud() {
    if (_gcloudConfigured) {
      return
    }
    _gcloudConfigured = true

    script.env.PATH = "/root/google-cloud-sdk/bin:${script.env.PATH}"

    script.sh "gcloud config set core/project ${project}"
    script.sh "gcloud config set core/disable_prompts True"
    if (cluster && zone) {
      script.sh "gcloud config set container/cluster ${cluster}"
      script.sh "gcloud config set compute/zone ${zone}"
      script.sh "gcloud container clusters get-credentials ${cluster}"
    }
  }

  @Deprecated
  def kubernetes(String deployment, image='', List<KubeContainer> containers=null) {
    configureGoogleCloud()

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

  @Deprecated
  def applyConfigMap(configMap) {
    configureGoogleCloud()

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

  @Deprecated
  def gsutil(String command) {
    configureGoogleCloud()
    script.sh "gsutil ${command}"
  }

  @Deprecated
  def gcloud(command) {
    configureGoogleCloud()
    script.sh "gcloud ${command}"
  }

  def gitTag(String custom='') {
    def version = custom ?: tag

    script.sshagent(['c983ed20-1b6c-41c0-b3c4-8411d7f8c482']) {
      script.sh "git tag -f ${version}"
      script.sh "git push --force origin refs/tags/${version}:refs/tags/${version}"
    }
  }

  def buildTag() {
    tag
  }

  @Deprecated
  def authContainers(Closure fn) {
    configureGoogleCloud()
    script.docker.withRegistry('https://eu.gcr.io', '35e93828-31ad-45fd-90a3-21a3c9dcf332') {
      fn()
    }
  }
}
