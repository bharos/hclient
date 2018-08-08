package org.apache.hadoop.hive.metastore.tools;

import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;

public class MeasureNotifications {

  DescriptiveStatistics stat;
  long startNotificationId;
  long endNotificationId;
  long totalTime;


  public double getEventsPerUnitTime() {
    return totalTime/(endNotificationId-startNotificationId);
  }
}
