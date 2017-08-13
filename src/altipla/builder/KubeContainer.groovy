
package altipla.builder;

import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.Whitelisted
import java.util.LinkedHashMap


class KubeContainer implements Serializable {
  String name, image, tag

  @Whitelisted
  KubeContainer(LinkedHashMap values) {
    if (values.containsKey("name")) {
      this.name = values["name"];
    }
    if (values.containsKey("image")) {
      this.image = values["image"];
    }
    if (values.containsKey("tag")) {
      this.tag = values["tag"];
    }
  }
}
