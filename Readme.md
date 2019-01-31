# Gather Crash Statistics

Data from https://unfallatlas.statistikportal.de/ converted the SHP to GeoJSON with QGIS.
(Don't like to add geotools as a dependency ;))

## Installation

The following steps can be omitted once 0.12 is out:

```
git clone com.graphhopper
cd com.graphhopper
git checkout 843f994
mvn clean install -DskipTests=true
```

Now create the crashstats project and run it:

```
git clone crashstats
cd crashstats
wget https://download.geofabrik.de/europe/germany-latest.osm.pbf
mvn clean install
java -Dgraphhopper.graph.location=graph-cache -Dgraphhopper.datareader.file=germany-latest.osm.pbf -jar target/crashstats*.jar
# open browser at localhost:8989/maps/
```