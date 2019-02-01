# Gather Crash Statistics

Data from https://unfallatlas.statistikportal.de/ converted the SHP to GeoJSON with QGIS.
(Don't like to add geotools as a dependency ;))

## Results

 * Using OSM data from early 2019 and crash data from 2017, the total length is 
   approx. 13000km or if you count the motorways as "two oneways" like we do in the code it is approx. 26000km
 * In Germany approx. 17000km of 26000km (total length) of the highway network are
   without a speed limit, i.e. there are at least 65.4% of the motorways without a speed limit
 * On road segments of a motorway without a limit there are 69.5% of the people die, 65.4% are badly
   injured and 57% are lightly injure.

## TODOs

 * When calculating the percentage of dead or badly injured people we need
   to consider [traffic count data](https://www.bast.de/BASt_2017/DE/Verkehrstechnik/Fachthemen/v2-verkehrszaehlung/Daten/2017_1/Jawe2017.html?cms_map=1&cms_filter=true&cms_jahr=Jawe2017&cms_land=&cms_strTyp=A&cms_str=&cms_dtvKfz=&cms_dtvSv=)
 * use older openstreetmap data to better match the situation for the older crash statistics
 * traffic sign data in openstreetmap might be wrong, check this by a view examples

## Installation

The following steps can be omitted once 0.12 is out:

```
git clone https://github.com/graphhopper/graphhopper/
cd graphhopper
git checkout 843f994
mvn clean install -DskipTests=true
```

Now create the crashstats project and run it:

```
git clone https://github.com/karussell/crashstats
cd crashstats
wget https://download.geofabrik.de/europe/germany-latest.osm.pbf
mvn clean install
java -Xmx3g -Xms3g -Dgraphhopper.graph.location=graph-cache -Dgraphhopper.datareader.file=germany-latest.osm.pbf -jar target/crashstats*.jar
# open browser at localhost:8989/maps/
```