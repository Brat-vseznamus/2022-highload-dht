package ok.dht.test.vihnin.code;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class ClusterManager {
    private final List<String> urls;
    private final List<String> neighbours;

    // O(n) memory
    public ClusterManager(List<String> urls) {
        this.urls = new ArrayList<>(urls);
        this.urls.sort(Comparator.naturalOrder());

        this.neighbours = new ArrayList<>();
        for (int j = 0; j < 2; j++) {
            neighbours.addAll(urls);
        }
    }

    public String getUrlByShard(int shard) {
        return urls.get(shard);
    }

    public int getShardByUrl(String url) {
        return urls.indexOf(url);
    }

    public int clusterSize() {
        return urls.size();
    }

    public List<String> getNeighbours(int shard) {
        return neighbours.subList(shard, urls.size() + shard);
    }
}
