package com.spotify.reaper.core;

import java.util.Set;

public class Cluster {
  private Long id;
  private final String partitioner; // String or actual class?
  private final String name;
  private final Set<String> seedHosts;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getPartitioner() {
    return partitioner;
  }

  public String getName() {
    return name;
  }

  public Set<String> getSeedHosts() {
    return seedHosts;
  }

  private Cluster(ClusterBuilder builder)
  {
    this.id = builder.id;
    this.partitioner = builder.partitioner;
    this.name = builder.name;
    this.seedHosts = builder.seedHosts;
  }


  public static class ClusterBuilder {
    private Long id;
    private String partitioner;
    private String name;
    private Set<String> seedHosts;

    public ClusterBuilder id(long id) {
      this.id = id;
      return this;
    }

    public ClusterBuilder partitioner(String partitioner) {
      this.partitioner = partitioner;
      return this;
    }

    public ClusterBuilder name(String name) {
      this.name = name;
      return this;
    }

    public ClusterBuilder seedHosts(Set<String> seedHosts) {
      this.seedHosts = seedHosts;
      return this;
    }


    public Cluster build() {
      return new Cluster(this);
    }
  }
}
