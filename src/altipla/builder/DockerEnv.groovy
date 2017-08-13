
package altipla.builder;

import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.Whitelisted
import java.util.LinkedHashMap


class DockerEnv implements Serializable {
  String name, value

  @Whitelisted
  DockerEnv(LinkedHashMap values) {
    if (values.containsKey("name")) {
      this.name = values["name"];
    }
    if (values.containsKey("value")) {
      this.value = values["value"];
    }
  }
}
