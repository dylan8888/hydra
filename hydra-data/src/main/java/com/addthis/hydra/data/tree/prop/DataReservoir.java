/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.addthis.hydra.data.tree.prop;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.math.RoundingMode;
import java.util.regex.Pattern;

import com.addthis.basis.util.ClosableIterator;
import com.addthis.basis.util.Varint;

import com.addthis.bundle.core.BundleField;
import com.addthis.bundle.value.AbstractCustom;
import com.addthis.bundle.value.Numeric;
import com.addthis.bundle.value.ValueArray;
import com.addthis.bundle.value.ValueFactory;
import com.addthis.bundle.value.ValueLong;
import com.addthis.bundle.value.ValueMap;
import com.addthis.bundle.value.ValueObject;
import com.addthis.bundle.value.ValueString;
import com.addthis.bundle.value.ValueTranslationException;
import com.addthis.codec.annotations.FieldConfig;
import com.addthis.codec.codables.BytesCodable;
import com.addthis.hydra.data.tree.DataTreeNode;
import com.addthis.hydra.data.tree.DataTreeNodeUpdater;
import com.addthis.hydra.data.tree.TreeDataParameters;
import com.addthis.hydra.data.tree.TreeNodeData;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.math.DoubleMath;

import org.apache.commons.math3.distribution.GeometricDistribution;
import org.apache.commons.math3.distribution.NormalDistribution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;

public class DataReservoir extends TreeNodeData<DataReservoir.Config> implements BytesCodable {

    private static final Logger log = LoggerFactory.getLogger(DataReservoir.class);

    private static final ImmutableList<DataTreeNode> EMPTY_LIST = ImmutableList.<DataTreeNode>builder().build();

    private static final byte[] EMPTY_BYTES = new byte[0];

    @FieldConfig(codable = true, required = true)
    private int[] reservoir;

    /**
     * The minEpoch is a monotonically increasing value.
     * An increase in this value is associated with the elimination
     * of state from older epochs. All effort is made to increment the
     * value as little as possible.
     */
    @FieldConfig(codable = true, required = true)
    private long minEpoch;

    private BundleField keyAccess;

    /**
     * This data attachment <span class="hydra-summary">keeps circular buffer
     * of N counters</span>.
     *
     * <p>The numbers of buckets that are stored is determined by the {@link #size}
     * parameter. The value stored in the {@link #epochField} bundle field determines
     * the current epoch. The counter stored within this epoch is incremented. Older
     * epochs are dropped as newer epochs are encountered.
     *
     * <p>The data attachment can be queried with the notation
     * {@code /+%name=epoch=N~percentile=N~obs=N~min=N~mode=modelfit}
     * Epoch determines the epoch to be tested. percentile is the Nth percentile
     * to use as a threshold. obs specifies how many previous observations to use. All these
     * fields are required. Specifying min=N is an optional parameter for a minimum number
     * of observations that must be detected. The output returned is of the form
     * {@code /delta:+hits/measurement:+hits/mean:+hits/stddev:+hits/mode:+hits/percentile:+hits}.
     *
     * <p>The data attachment can also be queried with the notation
     * {@code /$name=epoch||N~percentile||N~obs||N~min||N~mode||modelfit}.
     * The query parameters are identical to those from the previous paragraph
     * with the exception that "=" separator has been replaced with the "||" separator
     * (sideways equals?). The output returned is a value array with six elements.
     * The contents of the array are ["delta", "measurement", "mean", "stddev", "mode", "percentile"].
     *
     * @user-reference
     */
    public static final class Config extends TreeDataParameters<DataReservoir> {

        /**
         * Bundle field name from which to draw the epoch.
         * This field is required.
         */
        @FieldConfig(codable = true, required = true)
        private String epochField;

        /**
         * Size of the reservoir. This field is required.
         * @return
         */
        @FieldConfig(codable = true, required = true)
        private int size;

        @Override
        public DataReservoir newInstance() {
            return new DataReservoir();
        }
    }

    public DataReservoir() {

    }

    public DataReservoir(DataReservoir other) {
        reservoir = (other.reservoir == null) ? null : other.reservoir.clone();
        minEpoch = other.minEpoch;
    }

    public DataReservoir(long minEpoch, int[] reservoir) {
        this.minEpoch = minEpoch;
        this.reservoir = reservoir;
    }

    /**
     * Resize the reservoir to the new size.
     * If the reservoir has not yet been allocated then
     * construct it. If the requested length is smaller
     * than the reservoir then discard the oldest values.
     * If the requested length is larger than the reservoir
     * then allocate additional space for the reservoir.
     *
     * @param newsize new size of the reservoir
     */
    private void resize(int newsize) {
        if (reservoir == null) {
            reservoir = new int[newsize];
        } else if (reservoir.length < newsize) {
            int[] newReservoir = new int[newsize];
            System.arraycopy(reservoir, 0, newReservoir, 0, reservoir.length);
            reservoir = newReservoir;
        } else if (reservoir.length > newsize) {
            int[] newReservoir = new int[newsize];
            System.arraycopy(reservoir, reservoir.length - newsize, newReservoir, 0, newsize);
            minEpoch += (reservoir.length - newsize);
            reservoir = newReservoir;
        }
    }

    /**
     * Shift the minimum epoch to accommodate
     * the new epoch. If the epoch is less than the minimum
     * epoch then do nothing. If the epoch falls within the boundary
     * of the the reservoir then do nothing. If the epoch is farther
     * away then one length away from the maximum epoch, then empty
     * out the reservoir and set the maximum epoch to the target
     * epoch. Otherwise shift the reservoir to accommodate the new
     * epoch.
     *
     * @param epoch new epoch to accommodate
     */
    private void shift(long epoch) {
        long delta = (epoch - minEpoch);
        if (delta < reservoir.length) {
            // do nothing
        } else if (delta > 2 * (reservoir.length - 1)) {
            Arrays.fill(reservoir, 0);
            minEpoch = epoch - (reservoir.length - 1);
        } else {
            int shift = (int) (delta - reservoir.length + 1);
            System.arraycopy(reservoir, shift, reservoir, 0, reservoir.length - shift);
            Arrays.fill(reservoir, reservoir.length - shift, reservoir.length, 0);
            minEpoch += shift;
        }
    }

    /**
     * Insert the new epoch. Assumes that {@link #shift(long epoch)}
     * has previously been invoked.
     *
     * @param epoch new epoch to insert
     */
    private void update(long epoch, long count) {
        if (epoch >= minEpoch) {
            reservoir[(int) (epoch - minEpoch)] += count;
        }
    }

    public DataReservoir merge(DataReservoir other) {
        if (this.reservoir == null) {
            return new DataReservoir(other);
        } else if (other.reservoir == null) {
            return new DataReservoir(this);
        }
        long minEpoch1 = this.minEpoch;
        long minEpoch2 = other.minEpoch;
        long maxEpoch1 = this.minEpoch + this.reservoir.length;
        long maxEpoch2 = other.minEpoch + other.reservoir.length;
        long minEpoch = Math.min(minEpoch1, minEpoch2);
        long maxEpoch = Math.max(maxEpoch1, maxEpoch2);
        DataReservoir result = new DataReservoir();
        result.minEpoch = minEpoch;
        result.resize((int) (maxEpoch - minEpoch));
        for (long i = minEpoch; i < maxEpoch; i++) {
            long count1 = Math.max(0, this.retrieveCount(i));
            long count2 = Math.max(0, other.retrieveCount(i));
            result.update(i, count1 + count2);
        }
        return result;
    }

    /**
     * Update the reservoir with the input epoch and a value of one.
     *
     * @param epoch input time period
     * @param size alters the capacity of the reservoir
     */
    @VisibleForTesting
    void updateReservoir(long epoch, int size) {
        updateReservoir(epoch, size, 1);
    }

    /**
     * Update the reservoir with the input epoch and specified additional count.
     *
     * @param epoch input time period
     * @param size alters the capacity of the reservoir
     * @param count amount to increment the time period
     */
    @VisibleForTesting
    void updateReservoir(long epoch, int size, long count) {
        resize(size);
        shift(epoch);
        update(epoch, count);
    }

    /**
     * Return the count associated with the input epoch,
     * or an error value if the input is out of bounds.
     *
     * @param epoch target epoch
     * @return the non-negative count or -1 if input is less
     *         than minimum epoch or -2 if input is greater
     *         than maximum epoch or -3 if the data structure
     *         has not been initialized.
     */
    @VisibleForTesting
    int retrieveCount(long epoch) {
        if (reservoir == null) {
            return -3;
        } else if (epoch < minEpoch) {
            return -1;
        } else if (epoch >= (minEpoch + reservoir.length)) {
            return -2;
        } else {
            return reservoir[(int) (epoch - minEpoch)];
        }
    }

    @Override
    public boolean updateChildData(DataTreeNodeUpdater state,
            DataTreeNode childNode, DataReservoir.Config conf) {
        if (keyAccess == null) {
            keyAccess = state.getBundle().getFormat().getField(conf.epochField);
        }
        ValueObject val = state.getBundle().getValue(keyAccess);
        if (val != null) {
            try {
                long epoch = val.asLong().getLong();
                updateReservoir(epoch, conf.size);
                return true;
            } catch (Exception ex) {
                log.error("Error trying to insert " + val + " into reservoir: ", ex);
            }
        }
        return false;
    }

    /**
     * Helper method for {@link #getNodes(com.addthis.hydra.data.tree.DataTreeNode, String)}
     * If raw=true then add nodes for the raw observations.
     */
    private void addRawObservations(List<DataTreeNode> result, long targetEpoch, int numObservations) {

        if (targetEpoch < 0 || targetEpoch >= minEpoch + reservoir.length) {
            targetEpoch = minEpoch + reservoir.length - 1;
        }
        if (numObservations < 0 || numObservations > reservoir.length - 1) {
            numObservations = reservoir.length - 1;
        }

        int count = 0;
        int index = reservoir.length - 1;
        long currentEpoch = minEpoch + index;

        while (currentEpoch != targetEpoch) {
            index--;
            currentEpoch--;
        }

        /**
         * numObservations elements for the historical value.
         * Add one element to store for the target epoch.
         * Add one element to store the "minEpoch" node.
         */
        VirtualTreeNode[] children = new VirtualTreeNode[numObservations + 2];
        children[count++] = new VirtualTreeNode(Long.toString(currentEpoch), reservoir[index--]);

        while (count <= numObservations && index >= 0) {
            children[count++] = new VirtualTreeNode(Long.toString(minEpoch + index), reservoir[index--]);
        }

        while (count <= numObservations) {
            children[count++] = new VirtualTreeNode(Long.toString(minEpoch + index), 0);
            index--;
        }

        children[count] = new VirtualTreeNode("minEpoch", minEpoch);
        result.add(new VirtualTreeNode("observations", 1, children));
    }

    /**
     * Either generate some nodes for debugging purposes or
     * return an empty list.
     *
     * @param raw if true then generate debugging nodes
     * @return list of nodes
     */
    private List<DataTreeNode> makeDefaultNodes(boolean raw, long targetEpoch, int numObservations) {
        if (raw) {
            List<DataTreeNode> result = new ArrayList<>();
            addRawObservations(result, targetEpoch, numObservations);
            return result;
        } else {
            return EMPTY_LIST;
        }
    }

    /**
     * Convenience method to convert an node into an array of size one.
     */
    private static VirtualTreeNode[] generateSingletonArray(VirtualTreeNode value) {
        VirtualTreeNode[] result = new VirtualTreeNode[1];
        result[0] = value;
        return result;
    }

    private static double longToDouble(long value, boolean doubleToLongBits) {
        if (doubleToLongBits) {
            return Double.longBitsToDouble(value);
        } else {
            return (double) value;
        }
    }

    private static long doubleToLong(double value, boolean doubleToLongBits) {
        if (doubleToLongBits) {
            return Double.doubleToLongBits(value);
        } else {
            return DoubleMath.roundToLong(value, RoundingMode.HALF_UP);
        }
    }

    private ValueObject generateValueObject(String key, String separator) {
        long targetEpoch = -1;
        int numObservations = -1;
        double sigma = Double.POSITIVE_INFINITY;
        double percentile = 0;
        boolean doubleToLongBits = false;
        int minMeasurement = Integer.MIN_VALUE;
        boolean raw = false;
        String mode = "sigma";
        if (key == null) {
            return null;
        }
        String[] kvpairs = key.split(Pattern.quote("~"));
        for(String kvpair : kvpairs) {
            String[] kv = kvpair.split(Pattern.quote(separator));
            if (kv.length == 2) {
                String kvkey = kv[0];
                String kvvalue = kv[1];
                switch (kvkey) {
                    case "double":
                        doubleToLongBits = Boolean.parseBoolean(kvvalue);
                        break;
                    case "epoch":
                        targetEpoch = Long.parseLong(kvvalue);
                        break;
                    case "sigma":
                        sigma = Double.parseDouble(kvvalue);
                        break;
                    case "min":
                        minMeasurement = Integer.parseInt(kvvalue);
                        break;
                    case "obs":
                        numObservations = Integer.parseInt(kvvalue);
                        break;
                    case "raw":
                        raw = Boolean.parseBoolean(kvvalue);
                        break;
                    case "percentile":
                        percentile = Double.parseDouble(kvvalue);
                        break;
                    case "mode":
                        mode = kvvalue;
                        break;
                    default:
                        throw new RuntimeException("Unknown key " + kvkey);
                }
            }
        }
        if (mode.equals("get")) {
            long count = retrieveCount(targetEpoch);
            if (count < 0) {
                return ValueFactory.create(0L);
            } else {
                return ValueFactory.create(count);
            }
        } else {
            DataReservoirValue.Builder builder = new DataReservoirValue.Builder();
            builder.setTargetEpoch(targetEpoch);
            builder.setNumObservations(numObservations);
            builder.setDoubleToLongBits(doubleToLongBits);
            builder.setRaw(raw);
            builder.setSigma(sigma);
            builder.setMinMeasurement(minMeasurement);
            builder.setPercentile(percentile);
            builder.setMode(mode);
            DataReservoirValue value = builder.build(this);
            return value;
        }
    }

    private List<DataTreeNode> computeResult(DataReservoirValue value) {
        switch (value.mode) {
            case "sigma":
                return sigmaAnomalyDetection(value.targetEpoch, value.numObservations, value.doubleToLongBits,
                        value.raw, value.sigma, value.minMeasurement);
            case "modelfit":
                return modelFitAnomalyDetection(value.targetEpoch, value.numObservations, value.doubleToLongBits,
                        value.raw, value.percentile, value.minMeasurement);
            default:
                throw new RuntimeException("Unknown mode type '" + value.mode + "'");
        }

    }

    @Override
    public ValueObject getValue(String key) {
        if (key == null) {
            return null;
        }
        ValueObject value = generateValueObject(key, "||");
        if (value instanceof DataReservoirValue) {
            DataReservoirValue dataReservoirValue = (DataReservoirValue) value;
            value = dataReservoirValue.setDoubleToLongClone(true).setRawClone(false);
        }
        return value;
    }

    @Override
    public List<DataTreeNode> getNodes(DataTreeNode parent, String key) {
        if (key == null) {
            return null;
        }
        DataReservoirValue value = (DataReservoirValue) generateValueObject(key, "=");
        return computeResult(value);
    }

    private static void updateFrequencies(Map<Integer,Integer> frequencies, int value) {
        Integer count = frequencies.get(value);
        if (count == null) {
            count = 0;
        }
        frequencies.put(value, count + 1);
    }

    private double gaussianNegativeProbability(double mean, double stddev) {
        NormalDistribution distribution = new NormalDistribution(mean, stddev);
        return distribution.cumulativeProbability(0.0);
    }

    @VisibleForTesting
    List<DataTreeNode> modelFitAnomalyDetection(long targetEpoch, int numObservations,
            boolean doubleToLongBits, boolean raw, double percentile, int minMeasurement) {
        int measurement;
        int count = 0;
        int min = Integer.MAX_VALUE;

        if (targetEpoch < 0) {
            return makeDefaultNodes(raw, targetEpoch, numObservations);
        } else if (numObservations <= 0) {
            return makeDefaultNodes(raw, targetEpoch, numObservations);
        } else if (reservoir == null) {
            return makeDefaultNodes(raw, targetEpoch, numObservations);
        } else if (targetEpoch < minEpoch) {
            return makeDefaultNodes(raw, targetEpoch, numObservations);
        } else if (targetEpoch >= minEpoch + reservoir.length) {
            return makeDefaultNodes(raw, targetEpoch, numObservations);
        } else if (numObservations > (reservoir.length - 1)) {
            return makeDefaultNodes(raw, targetEpoch, numObservations);
        }

        /**
         * Fitting to a geometric distribution uses the mean value of the sample.
         *
         * Fitting to a normal distribution uses the Apache Commons Math implementation.
         */
        double mean = 0.0;
        double m2 = 0.0;
        double stddev;
        double gaussianNegative = -1.0;
        Map<Integer,Integer> frequencies = new HashMap<>();
        double threshold;
        double measurePercentile = -100.0;

        int index = reservoir.length - 1;
        long currentEpoch = minEpoch + index;

        while (currentEpoch != targetEpoch) {
            index--;
            currentEpoch--;
        }

        measurement = reservoir[index--];
        currentEpoch--;

        while (count < numObservations && index >= 0) {
            int value = reservoir[index--];
            if (value < min) {
                min = value;
            }
            updateFrequencies(frequencies, value);
            count++;
            double delta = value - mean;
            mean += delta / count;
            m2 += delta * (value - mean);
        }

        while (count < numObservations) {
            int value = 0;
            if (value < min) {
                min = value;
            }
            updateFrequencies(frequencies, value);
            count++;
            double delta = value - mean;
            mean += delta / count;
            m2 += delta * (value - mean);
        }

        if (count < 2) {
            stddev = 0.0;
        } else {
            stddev = Math.sqrt(m2 / count);
        }

        int mode = -1;
        int modeCount = -1;

        for(Map.Entry<Integer,Integer> entry : frequencies.entrySet()) {
            int key = entry.getKey();
            int value = entry.getValue();
            if (value > modeCount || (value == modeCount && key > mode)) {
                mode = key;
                modeCount = value;
            }
        }

        if (mean > 0.0 && stddev > 0.0) {
            gaussianNegative = gaussianNegativeProbability(mean, stddev);
        }

        if (mean == 0.0) {
            threshold = 0.0;
        } else if (stddev == 0.0) {
            threshold = mean;
        } else if (mean > 1.0) {
            NormalDistribution distribution = new NormalDistribution(mean, stddev);
            double badProbability = distribution.cumulativeProbability(0.0);
            double goodProbability = badProbability + (1.0 - badProbability) * (percentile / 100.0);
            threshold = distribution.inverseCumulativeProbability(goodProbability);
            measurePercentile = distribution.probability(0.0, measurement) / (1.0 - badProbability) * 100.0;
        } else {
            double p = 1.0 / (1.0 + mean);
            GeometricDistribution distribution = new GeometricDistribution(p);
            threshold = distribution.inverseCumulativeProbability(percentile / 100.0);
            measurePercentile = distribution.cumulativeProbability(measurement) * 100.0;
        }

        List<DataTreeNode> result = new ArrayList<>();
        VirtualTreeNode vchild, vparent;

        if (measurement >= minMeasurement && (measurement > threshold || percentile == 0.0)) {
            vchild = new VirtualTreeNode("gaussianNegative",
                    doubleToLong(gaussianNegative, doubleToLongBits));
            vparent = new VirtualTreeNode("percentile",
                    doubleToLong(measurePercentile, doubleToLongBits), generateSingletonArray(vchild));
            vchild = vparent;
            vparent = new VirtualTreeNode("mode", mode, generateSingletonArray(vchild));
            vchild = vparent;
            vparent = new VirtualTreeNode("stddev",
                    doubleToLong(stddev, doubleToLongBits), generateSingletonArray(vchild));
            vchild = vparent;
            vparent = new VirtualTreeNode("mean",
                    doubleToLong(mean, doubleToLongBits), generateSingletonArray(vchild));
            vchild = vparent;
            vparent = new VirtualTreeNode("measurement",
                    measurement, generateSingletonArray(vchild));
            vchild = vparent;
            vparent = new VirtualTreeNode("delta",
                    doubleToLong(measurement - threshold, doubleToLongBits), generateSingletonArray(vchild));
            result.add(vparent);
            if (raw) {
                addRawObservations(result, targetEpoch, numObservations);
            }
        } else {
            makeDefaultNodes(raw, targetEpoch, numObservations);
        }
        return result;
    }

    private List<DataTreeNode> sigmaAnomalyDetection(long targetEpoch, int numObservations, boolean doubleToLongBits,
            boolean raw, double sigma, int minMeasurement) {

        int measurement;
        if (targetEpoch < 0) {
            return makeDefaultNodes(raw, targetEpoch, numObservations);
        } else if (sigma == Double.POSITIVE_INFINITY) {
            return makeDefaultNodes(raw, targetEpoch, numObservations);
        } else if (numObservations <= 0) {
            return makeDefaultNodes(raw, targetEpoch, numObservations);
        } else if (reservoir == null) {
            return makeDefaultNodes(raw, targetEpoch, numObservations);
        } else if (targetEpoch < minEpoch) {
            return makeDefaultNodes(raw, targetEpoch, numObservations);
        } else if (targetEpoch >= minEpoch + reservoir.length) {
            return makeDefaultNodes(raw, targetEpoch, numObservations);
        } else if (numObservations > (reservoir.length - 1)) {
            return makeDefaultNodes(raw, targetEpoch, numObservations);
        }

        int count = 0;
        double mean = 0.0;
        double m2 = 0.0;
        double stddev;

        int index = reservoir.length - 1;
        long currentEpoch = minEpoch + index;

        while (currentEpoch != targetEpoch) {
            index--;
            currentEpoch--;
        }

        measurement = reservoir[index--];

        while (count < numObservations && index >= 0) {
            int value = reservoir[index--];
            count++;
            double delta = value - mean;
            mean += delta / count;
            m2 += delta * (value - mean);
        }

        while (count < numObservations) {
            int value = 0;
            count++;
            double delta = value - mean;
            mean += delta / count;
            m2 += delta * (value - mean);
        }

        if (count < 2) {
            stddev = 0.0;
        } else {
            stddev = Math.sqrt(m2 / count);
        }

        double delta = (measurement - (sigma * stddev + mean));

        VirtualTreeNode vchild, vparent;
        if (delta >= 0 && measurement >= minMeasurement) {
            List<DataTreeNode> result = new ArrayList<>();
            vchild = new VirtualTreeNode("threshold",
                    doubleToLong(sigma * stddev + mean, doubleToLongBits));
            vparent = new VirtualTreeNode("stddev",
                    doubleToLong(stddev, doubleToLongBits), generateSingletonArray(vchild));
            vchild = vparent;
            vparent = new VirtualTreeNode("mean",
                    doubleToLong(mean, doubleToLongBits), generateSingletonArray(vchild));
            vchild = vparent;
            vparent = new VirtualTreeNode("measurement",
                    measurement, generateSingletonArray(vchild));
            vchild = vparent;
            vparent = new VirtualTreeNode("delta",
                    doubleToLong(delta, doubleToLongBits), generateSingletonArray(vchild));
            result.add(vparent);
            if (raw) {
                addRawObservations(result, targetEpoch, numObservations);
            }
            return result;
        } else {
            return makeDefaultNodes(raw, targetEpoch, numObservations);
        }
    }

    static final class DataReservoirValue extends AbstractCustom<DataReservoir>
            implements Numeric {

        long targetEpoch;
        int numObservations;
        boolean doubleToLongBits;
        boolean raw;
        double percentile;
        double sigma;
        int minMeasurement;
        String mode;

        /**
         * The Builder pattern allows many different variations of a class to
         * be instantiated without the pitfalls of complex constructors. See
         * ''Effective Java, Second Edition.'' Item 2 - "Consider a builder when
         * faced with many constructor parameters."
         */
        static class Builder {
            long targetEpoch = -1;
            int numObservations = -1;
            boolean doubleToLongBits = false;
            boolean raw = false;
            double percentile = 0;
            double sigma = Double.POSITIVE_INFINITY;
            int minMeasurement = Integer.MIN_VALUE;
            String mode = "sigma";

            Builder setTargetEpoch(long targetEpoch) {
                this.targetEpoch = targetEpoch;
                return this;
            }

            Builder setNumObservations(int numObservations) {
                this.numObservations = numObservations;
                return this;
            }

            Builder setDoubleToLongBits(boolean doubleToLongBits) {
                this.doubleToLongBits = doubleToLongBits;
                return this;
            }

            Builder setRaw(boolean raw) {
                this.raw = raw;
                return this;
            }

            Builder setPercentile(double percentile) {
                this.percentile = percentile;
                return this;
            }

            Builder setSigma(double sigma) {
                this.sigma = sigma;
                return this;
            }

            Builder setMinMeasurement(int minMeasurement) {
                this.minMeasurement = minMeasurement;
                return this;
            }

            Builder setMode(String mode) {
                this.mode = mode;
                return this;
            }

            DataReservoirValue build(DataReservoir reservoir) {
                return new DataReservoirValue(reservoir, targetEpoch, numObservations,
                        doubleToLongBits, raw, percentile, sigma, minMeasurement, mode);
            }
        }

        @SuppressWarnings("unused")
        public DataReservoirValue() {
            super(null);
        }

        private DataReservoirValue(DataReservoir reservoir, long targetEpoch, int numObservations,
                boolean doubleToLongBits, boolean raw, double percentile, double sigma,
                int minMeasurement, String mode) {
            super(reservoir);
            this.targetEpoch = targetEpoch;
            this.numObservations = numObservations;
            this.doubleToLongBits = doubleToLongBits;
            this.raw = raw;
            this.percentile = percentile;
            this.sigma = sigma;
            this.minMeasurement = minMeasurement;
            this.mode = mode;
        }

        public DataReservoirValue setRawClone(boolean raw) {
            return new DataReservoirValue(asNative(), targetEpoch, numObservations,
                    doubleToLongBits, raw, percentile, sigma, minMeasurement, mode);
        }

        public DataReservoirValue setDoubleToLongClone(boolean doubleToLongBits) {
            return new DataReservoirValue(asNative(), targetEpoch, numObservations,
                    doubleToLongBits, raw, percentile, sigma, minMeasurement, mode);
        }


        @Override
        public Numeric sum(Numeric val) {
            return new DataReservoirValue(asNative().merge((DataReservoir) val.asNative()),
                    targetEpoch, numObservations, doubleToLongBits, raw, percentile,
                    sigma, minMeasurement, mode);
        }

        @Override
        public Numeric diff(Numeric val) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Numeric prod(Numeric val) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Numeric divide(Numeric val) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Numeric avg(int count) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Numeric min(Numeric val) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Numeric max(Numeric val) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ValueMap asMap() {
            ValueMap result = ValueFactory.createMap();
            result.put("targetEpoch", ValueFactory.create(targetEpoch));
            result.put("numObservations", ValueFactory.create(numObservations));
            result.put("doubleToLongBits", ValueFactory.create(doubleToLongBits ? 1 : 0));
            result.put("raw", ValueFactory.create(raw ? 1 : 0));
            result.put("percentile", ValueFactory.create(percentile));
            result.put("sigma", ValueFactory.create(sigma));
            result.put("minMeasurement", ValueFactory.create(minMeasurement));
            result.put("mode", ValueFactory.create(mode));
            result.put("minEpoch", ValueFactory.create(heldObject.minEpoch));
            ValueArray reservoir = ValueFactory.createArray(heldObject.reservoir.length);
            for (int i = 0; i < heldObject.reservoir.length; i++) {
                reservoir.add(i, ValueFactory.create(heldObject.reservoir[i]));
            }
            result.put("reservoir", reservoir);
            return result;
        }

        @Override
        public void setValues(ValueMap map) {
            targetEpoch = map.get("targetEpoch").asLong().asNative();
            numObservations = map.get("numObservations").asLong().asNative().intValue();
            doubleToLongBits = map.get("doubleToLongBits").asLong().asNative().intValue() == 1;
            raw = map.get("raw").asLong().asNative().intValue() == 1;
            percentile = map.get("percentile").asDouble().asNative();
            sigma = map.get("sigma").asDouble().asNative();
            minMeasurement = map.get("minMeasurement").asLong().asNative().intValue();
            mode = map.get("mode").asString().asNative();
            long minEpoch = map.get("minEpoch").asLong().asNative();
            ValueArray reservoirValueObject = map.get("reservoir").asArray();
            int size = reservoirValueObject.size();
            int[] reservoir = new int[size];
            for (int i = 0; i < size; i++) {
                reservoir[i] = reservoirValueObject.get(i).asLong().asNative().intValue();
            }
            this.heldObject = new DataReservoir(minEpoch, reservoir);
        }

        @Override
        public ValueLong asLong() { return ValueFactory.create(asArray().size()); }

        @Override
        public ValueArray asArray() throws ValueTranslationException {
            ValueArray result = ValueFactory.createArray(1);
            List<DataTreeNode> list = asNative().computeResult(this);
            ClosableIterator<DataTreeNode> iterator;
            DataTreeNode current;
            switch (mode) {
                case "modelfit":
                    current = list.get(0);
                    result.add(ValueFactory.create(longToDouble(current.getCounter(), doubleToLongBits))); // 'delta'
                    iterator = current.getIterator();
                    current = iterator.next();
                    iterator.close();
                    result.add(ValueFactory.create(current.getCounter()));                                // 'measurement'
                    iterator = current.getIterator();
                    current = iterator.next();
                    iterator.close();
                    result.add(ValueFactory.create(longToDouble(current.getCounter(), doubleToLongBits))); // 'mean'
                    iterator = current.getIterator();
                    current = iterator.next();
                    iterator.close();
                    result.add(ValueFactory.create(longToDouble(current.getCounter(), doubleToLongBits))); // 'stddev'
                    iterator = current.getIterator();
                    current = iterator.next();
                    iterator.close();
                    result.add(ValueFactory.create(current.getCounter()));                                 // 'mode'
                    iterator = current.getIterator();
                    current = iterator.next();
                    iterator.close();
                    result.add(ValueFactory.create(longToDouble(current.getCounter(), doubleToLongBits))); // 'percentile'
                    break;
                case "sigma":
                    current = list.get(0);
                    result.add(ValueFactory.create(longToDouble(current.getCounter(), doubleToLongBits))); // 'delta'
                    iterator = current.getIterator();
                    current = iterator.next();
                    iterator.close();
                    result.add(ValueFactory.create(current.getCounter()));                                 // 'measurement'
                    iterator = current.getIterator();
                    current = iterator.next();
                    iterator.close();
                    result.add(ValueFactory.create(longToDouble(current.getCounter(), doubleToLongBits))); // 'mean'
                    iterator = current.getIterator();
                    current = iterator.next();
                    iterator.close();
                    result.add(ValueFactory.create(longToDouble(current.getCounter(), doubleToLongBits))); // 'stddev'
                    iterator = current.getIterator();
                    current = iterator.next();
                    iterator.close();
                    result.add(ValueFactory.create(longToDouble(current.getCounter(), doubleToLongBits))); // 'threshold'
                    break;
                default:
                    throw new RuntimeException("Unknown mode type '" + mode + "'");
            }
            return result;
        }

        @Override
        public ValueString asString() throws ValueTranslationException {
            throw new ValueTranslationException();
        }
    }

    @Override
    public byte[] bytesEncode(long version) {
        if (reservoir == null) {
            return EMPTY_BYTES;
        }
        byte[] retBytes = null;
        ByteBuf byteBuf = PooledByteBufAllocator.DEFAULT.buffer();
        try {
            Varint.writeUnsignedVarLong(minEpoch, byteBuf);
            Varint.writeUnsignedVarInt(reservoir.length, byteBuf);
            for(int element : reservoir) {
                Varint.writeUnsignedVarInt(element, byteBuf);
            }
            retBytes = new byte[byteBuf.readableBytes()];
            byteBuf.readBytes(retBytes);
        } finally {
            byteBuf.release();
        }
        return retBytes;
    }

    @Override
    public void bytesDecode(byte[] b, long version) {
        if (b.length == 0) {
            return;
        }
        ByteBuf byteBuf = Unpooled.wrappedBuffer(b);
        try {
            minEpoch = Varint.readUnsignedVarLong(byteBuf);
            int length = Varint.readUnsignedVarInt(byteBuf);
            reservoir = new int[length];
            for (int i = 0; i < reservoir.length; i++) {
                reservoir[i] = Varint.readUnsignedVarInt(byteBuf);
            }
        } finally {
            byteBuf.release();
        }
    }
}
