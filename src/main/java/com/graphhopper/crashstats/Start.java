package com.graphhopper.crashstats;

import com.bedatadriven.jackson.datatype.jts.JtsModule;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graphhopper.GraphHopper;
import com.graphhopper.json.geo.JsonFeature;
import com.graphhopper.json.geo.JsonFeatureCollection;
import com.graphhopper.reader.osm.GraphHopperOSM;
import com.graphhopper.routing.profiles.*;
import com.graphhopper.routing.util.*;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.storage.StorableProperties;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.storage.index.QueryResult;
import com.graphhopper.util.CmdArgs;
import com.graphhopper.util.EdgeIteratorState;
import org.locationtech.jts.geom.Coordinate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

public class Start {

    public static void main(String[] args) {
        new Start().start(args);
    }

    private void start(String[] args) {
        final Map<String, IntEncodedValue> JSON_PROPERTIES = new HashMap<>();

        final String crashLocation = "Unfall-export.geojson.gz";
        final String trafficCountsLocation = "Jawe2017.csv";
        final ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JtsModule());
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        final Logger logger = LoggerFactory.getLogger(Start.class);

        GraphHopper hopper = new GraphHopperOSM() {

            @Override
            public void postProcessing() {
                // for data import
                initLocationIndex();

                DecimalEncodedValue trafficEnc = getEncodingManager().getEncodedValue("traffic", DecimalEncodedValue.class);
                importTrafficCountFile("traffic", new File(trafficCountsLocation), trafficEnc);

                IntEncodedValue crashEnc = getEncodingManager().getEncodedValue("crash", IntEncodedValue.class);
                importFile("crashes", new File(crashLocation), crashEnc);

                // skip for now super.postProcessing();
            }

            // TODO store traffic count on both sides of the motorway
            void importTrafficCountFile(String name, File file, DecimalEncodedValue enc) {
                StorableProperties props = getGraphHopperStorage().getProperties();
                String storageKey = "files." + name + ".preprocessing";
                String preprocessing = props.get(storageKey);
                if (!preprocessing.isEmpty()) {
                    logger.info("file " + file + " already preprocessed " + name);
                    return;
                }

                String latStr = "Koor_WGS84_N", lonStr = "Koor_WGS84_E", trafficCountStr = "DTV_Kfz_MobisSo_Q";
                int latIdx = -1, lonIdx = -1, invalid = 0, trafficCountIdx = -1;
                LocationIndex index = getLocationIndex();

                // try the following if we need the strings new InputStreamReader(new FileInputStream(file), Charset.forName("ISO-8859-1");)
                try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                    String line;
                    int lineCounter = 0;
                    while ((line = reader.readLine()) != null) {
                        String[] strs = line.split(";");
                        if (lineCounter == 0) {
                            latIdx = Arrays.asList(strs).indexOf(latStr);
                            lonIdx = Arrays.asList(strs).indexOf(lonStr);
                            trafficCountIdx = Arrays.asList(strs).indexOf(trafficCountStr);
                            if (latIdx < 0 || lonIdx < 0 || trafficCountIdx < 0)
                                throw new RuntimeException("Cannot find header " + latStr + " or " + lonStr + " or " + trafficCountStr);
                            // skip header
                            lineCounter++;
                            continue;
                        }
                        lineCounter++;
                        double lat = Double.parseDouble(strs[latIdx].replace(",", ".")),
                                lon = Double.parseDouble(strs[lonIdx].replace(",", "."));
                        QueryResult qr = index.findClosest(lat, lon, EdgeFilter.ALL_EDGES);
                        // skip items too far away from the road or no traffic count in the line
                        if (qr.getQueryDistance() > 20 || strs[trafficCountIdx].isEmpty()) {
                            invalid++;
                            continue;
                        }

                        EdgeIteratorState edge = qr.getClosestEdge();
                        // remove separator for "thousands"
                        try {
                            edge.set(enc, (double) Integer.parseInt(strs[trafficCountIdx].replace(".", "")));
                        } catch (NumberFormatException ex) {
                            invalid++;
                            logger.info(lineCounter + " problem with " + strs[trafficCountIdx]);
                        }
                    }
                    props.put(storageKey, "done").flush();
                    logger.info(name + " loaded " + (lineCounter - 1) + " (invalid " + invalid + ")");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            void importFile(String name, File file, IntEncodedValue enc) {
                StorableProperties props = getGraphHopperStorage().getProperties();
                String storageKey = "files." + name + ".preprocessing";
                String preprocessing = props.get(storageKey);
                if (!preprocessing.isEmpty()) {
                    logger.info("file " + file + " already preprocessed " + name);
                    return;
                }

                LocationIndex index = getLocationIndex();
                try {
                    JsonFeatureCollection coll = mapper.readValue(new GZIPInputStream(new FileInputStream(file)), JsonFeatureCollection.class);
                    int invalid = 0;
                    for (JsonFeature feature : coll.getFeatures()) {
                        Coordinate coord = feature.getGeometry().getCoordinate();
                        QueryResult qr = index.findClosest(coord.y, coord.x, EdgeFilter.ALL_EDGES);
                        // skip items too far away from the road
                        if (qr.getQueryDistance() > 20) {
                            invalid++;
                            continue;
                        }

                        EdgeIteratorState edge = qr.getClosestEdge();
                        // default is zero
                        edge.set(enc, edge.get(enc) + 1);
                        for (Map.Entry<String, IntEncodedValue> entry : JSON_PROPERTIES.entrySet()) {
                            Object obj = feature.getProperties().get(entry.getKey().toUpperCase(Locale.ROOT));
                            if (obj != null) {
                                try {
                                    int integ = Integer.parseInt((String) obj);
                                    edge.set(entry.getValue(), integ);
                                } catch (NumberFormatException ex) {
                                    logger.warn(entry.getKey() + " has wrong number format " + obj);
                                }
                            } else logger.warn(entry.getKey() + " is null");
                        }
//                        System.out.println(edge.getName());
                    }
                    props.put(storageKey, "done").flush();
                    logger.info(name + " loaded " + coll.getFeatures().size() + " (invalid " + invalid + ")");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

            }
        }.init(CmdArgs.read(args));

        EncodingManager.Builder builder = new EncodingManager.Builder(8);
        IntEncodedValue crashEnc = new SimpleIntEncodedValue("crash", 6, false);
        builder.add(crashEnc);
        // store with less precision (rounding via factor 40)
        IntEncodedValue trafficCountEnc = new FactorizedDecimalEncodedValue("traffic", 12, 40, false);
        builder.add(trafficCountEnc);

        // store JSON properties in graph
        // explained in German in meta.txt but what is UTYP vs. UTYP1?
        for (String prop : Arrays.asList("uart", "ukategorie", "utyp1", "licht", "strzustand", "uwochentag")) {
            IntEncodedValue enc = new SimpleIntEncodedValue(prop, 4, false);
            JSON_PROPERTIES.put(prop, enc);
            builder.add(enc);
        }

        CarFlagEncoder carFlagEncoder = new CarFlagEncoder();
        builder.add(carFlagEncoder);

        // the next code will be likely already part of 0.12
        // START 0.12
        DecimalEncodedValue maxSpeedEnc = new FactorizedDecimalEncodedValue("max_speed", 8, 5, false);
        builder.put(maxSpeedEnc, (intsRef, readerWay, allowed, relationFlags) -> {
            double maxSpeed = AbstractFlagEncoder.parseSpeed(readerWay.getTag("maxspeed"));
            double fwdSpeed = AbstractFlagEncoder.parseSpeed(readerWay.getTag("maxspeed:forward"));
            if (fwdSpeed >= 0 && (maxSpeed < 0 || fwdSpeed < maxSpeed))
                maxSpeed = fwdSpeed;

            double backSpeed = AbstractFlagEncoder.parseSpeed(readerWay.getTag("maxspeed:backward"));
            if (backSpeed >= 0 && (maxSpeed < 0 || backSpeed < maxSpeed))
                maxSpeed = backSpeed;

            if (maxSpeed >= 0)
                maxSpeedEnc.setDecimal(false, intsRef, maxSpeed);
            return intsRef;
        });

        List<RoadClass> roadClasses = RoadClass.create("_default", "motorway", "motorway_link", "motorroad", "trunk", "trunk_link",
                "primary", "primary_link", "secondary", "secondary_link", "tertiary", "tertiary_link", "residential", "unclassified",
                "service", "road", "track", "forestry", "steps", "cycleway", "path", "living_street");
        final Map<String, RoadClass> roadClassMap = new HashMap<>(roadClasses.size());
        for (RoadClass rc : roadClasses) {
            roadClassMap.put(rc.toString(), rc);
        }
        ObjectEncodedValue roadClassEnc = new MappedObjectEncodedValue("road_class", roadClasses);
        builder.put(roadClassEnc, (intsRef, readerWay, allowed, relationFlags) -> {
            String highway = readerWay.getTag("highway");
            RoadClass rc = roadClassMap.get(highway);
            if (rc != null)
                roadClassEnc.setObject(false, intsRef, rc);

            return intsRef;
        });
        // END 0.12

        hopper.setEncodingManager(builder.build());

        hopper.setCHEnabled(false);
        hopper.importOrLoad();
        GraphHopperStorage graph = hopper.getGraphHopperStorage();

        // spread traffic information until next; do this only for motorway
//        RoadClass motorwayRC = roadClassMap.get("motorway");
//        graph.createEdgeExplorer(edgeIteratorState -> edgeIteratorState.get(roadClassEnc).equals(motorwayRC));
//        DijkstraBidirectionRef dijkstra = new DijkstraBidirectionRef(graph, new ShortestWeighting(carFlagEncoder), TraversalMode.NODE_BASED) {
//            {   initFrom(nodeOfTrafficCounter, 0);
//                runAlgo();
//            }
//        };

        logger.info("start gathering statistics");
        Map<String, Integer> countMap = new HashMap<>();
        AllEdgesIterator iter = graph.getAllEdges();
        int motorwayUnlimited = 0;
        double meterUnlimited = 0;
        double meterLimited = 0;
        double meterUnknown = 0;
        int kategorieUnlimited[] = new int[4];
        int kategorieLimited[] = new int[4];
        int kategorieUnkown[] = new int[4];
        IntEncodedValue kategorieEnc = hopper.getEncodingManager().getEncodedValue("ukategorie", IntEncodedValue.class);
        while (iter.next()) {
            String key = iter.get(roadClassEnc).toString();
            Integer count = countMap.get(key);
            countMap.put(key, (count == null ? 0 : count) + iter.get(crashEnc));

//            if (iter.get(trafficCountEnc) <= 0)
//                continue;

            if (key.equals("motorway")) {
                double val = iter.get(maxSpeedEnc);
                // 140 means explicitly unlimited, 0 means unknown speed limit
                if (val == 140) {
                    motorwayUnlimited += iter.get(crashEnc);
                    meterUnlimited += iter.getDistance();
                    kategorieUnlimited[iter.get(kategorieEnc)]++;
                } else if (val == 0) {
                    meterUnknown += iter.getDistance();
                    kategorieUnkown[iter.get(kategorieEnc)]++;
                } else {
                    meterLimited += iter.getDistance();
                    kategorieLimited[iter.get(kategorieEnc)]++;
                }
            }
        }

        for (Map.Entry<String, Integer> entry : countMap.entrySet()) {
            System.out.println(entry.getKey() + ": " + entry.getValue());
        }

        System.out.println();
        System.out.println("motorway: " + countMap.get("motorway"));
        System.out.println("motorway unlimited: " + motorwayUnlimited);
        System.out.println();
        System.out.println("km limited: " + meterLimited / 1000f);
        System.out.println("km unlimited: " + meterUnlimited / 1000f);
        System.out.println("km unknown: " + meterUnknown / 1000f);
        System.out.println();
        System.out.println("kategorie limited: " + Arrays.toString(kategorieLimited));
        System.out.println("kategorie unlimited: " + Arrays.toString(kategorieUnlimited));
        System.out.println("kategorie unknown: " + Arrays.toString(kategorieUnkown));
    }
}
