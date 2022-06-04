package de.ialistannen.lighthouse.updater;

import de.ialistannen.lighthouse.model.LighthouseContainerUpdate;
import java.util.List;

public interface UpdateListener {

  void onUpdatesFound(List<LighthouseContainerUpdate> updates);
}
