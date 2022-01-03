package de.ialistannen.lighthouse.updates;

import de.ialistannen.lighthouse.model.LighthouseContainerUpdate;
import java.util.List;

/**
 * Filters updates before they are passed on to the notifiers.
 */
public interface UpdateFilter {

  /**
   * Filters images. Must ensure that no uncommitted (see {@link #commit()}) state is used in the filter.
   *
   * @param updates the updates to filter
   * @return the filtered list
   * @throws FilterException if any error occurs
   */
  List<LighthouseContainerUpdate> filter(List<LighthouseContainerUpdate> updates) throws FilterException;

  /**
   * Commits any new state that might have been computed after {@link #filter(List)}.
   *
   * @throws FilterException if any error occurs
   */
  void commit() throws FilterException;
}
