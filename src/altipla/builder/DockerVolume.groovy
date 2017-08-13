
package altipla.builder;

import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.Whitelisted
import java.util.LinkedHashMap


class DockerVolume implements Serializable {
  String source, dest

  @Whitelisted
  DockerVolume(LinkedHashMap values) {
    if (values.containsKey("source")) {
      this.source = values["source"];
    }
    if (values.containsKey("dest")) {
      this.dest = values["dest"];
    }
  }
}
