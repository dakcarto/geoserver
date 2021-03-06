/* (c) 2014-2015 Open Source Geospatial Foundation - all rights reserved
 * (c) 2014 OpenPlans
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.catalog;

import it.geosolutions.imageio.maskband.DatasetLayout;
import it.geosolutions.imageio.utilities.ImageIOUtilities;

import java.awt.Rectangle;
import java.awt.image.ColorModel;
import java.awt.image.RenderedImage;
import java.awt.image.SampleModel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.media.jai.ImageLayout;
import javax.media.jai.RasterFactory;

import org.apache.commons.lang.ArrayUtils;
import org.geoserver.catalog.CoverageDimensionCustomizerReader.GridCoverageWrapper;
import org.geoserver.catalog.CoverageDimensionCustomizerReader.WrappedSampleDimension;
import org.geoserver.catalog.CoverageView.CoverageBand;
import org.geoserver.catalog.CoverageView.InputCoverageBand;
import org.geoserver.catalog.impl.CoverageDimensionImpl;
import org.geotools.coverage.CoverageFactoryFinder;
import org.geotools.coverage.GridSampleDimension;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.coverage.grid.io.GridCoverage2DReader;
import org.geotools.coverage.grid.io.OverviewPolicy;
import org.geotools.coverage.grid.io.StructuredGridCoverage2DReader;
import org.geotools.coverage.processing.CoverageProcessor;
import org.geotools.data.DataSourceException;
import org.geotools.data.ResourceInfo;
import org.geotools.data.ServiceInfo;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.factory.Hints;
import org.geotools.geometry.GeneralEnvelope;
import org.geotools.parameter.DefaultParameterDescriptor;
import org.geotools.parameter.DefaultParameterDescriptorGroup;
import org.geotools.parameter.ParameterGroup;
import org.geotools.referencing.CRS;
import org.jaitools.imageutils.ImageLayout2;
import org.opengis.coverage.SampleDimension;
import org.opengis.coverage.grid.Format;
import org.opengis.coverage.grid.GridEnvelope;
import org.opengis.filter.FilterFactory2;
import org.opengis.parameter.GeneralParameterDescriptor;
import org.opengis.parameter.GeneralParameterValue;
import org.opengis.parameter.ParameterDescriptor;
import org.opengis.parameter.ParameterValue;
import org.opengis.parameter.ParameterValueGroup;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.datum.PixelInCell;
import org.opengis.referencing.operation.MathTransform;

/**
 * A {@link CoverageView} reader which takes care of doing underlying coverage read operations and recompositions.
 * 
 * @author Daniele Romagnoli, GeoSolutions SAS
 * 
 */
public class CoverageViewReader implements GridCoverage2DReader {

    public final static FilterFactory2 FF = CommonFactoryFinder.getFilterFactory2();

    private static final CoverageProcessor PROCESSOR = CoverageProcessor.getInstance();

    /**
     * A CoveragesConsistencyChecker checks if the composing coverages respect the constraints which currently are:
     * <UL>
     * <LI>same CRS</LI>
     * <LI>same resolution</LI>
     * <LI>same bbox</LI>
     * <LI>same data type</LI>
     * <LI>same dimensions (same number of dimension, same type, and same name)</LI>
     * </UL>
     */
    static class CoveragesConsistencyChecker {

        private static double DELTA = 1E-10;

        private Set<ParameterDescriptor<List>> dynamicParameters;

        private String[] metadataNames;

        private GridEnvelope gridRange;

        private GeneralEnvelope envelope;

        private CoordinateReferenceSystem crs;

        private ImageLayout layout;

        public CoveragesConsistencyChecker(GridCoverage2DReader reader) throws IOException {
            envelope = reader.getOriginalEnvelope();
            gridRange = reader.getOriginalGridRange();
            crs = reader.getCoordinateReferenceSystem();
            metadataNames = reader.getMetadataNames();
            dynamicParameters = reader.getDynamicParameters();
            layout = reader.getImageLayout();
        }

        /**
         * Check whether the coverages associated to the provided reader is consistent with the reference coverage.
         * 
         * @param reader
         * @throws IOException
         */
        public void checkConsistency(GridCoverage2DReader reader) throws IOException {
            GeneralEnvelope envelope = reader.getOriginalEnvelope();
            GridEnvelope gridRange = reader.getOriginalGridRange();
            CoordinateReferenceSystem crs = reader.getCoordinateReferenceSystem();
            String[] metadataNames = reader.getMetadataNames();
            Set<ParameterDescriptor<List>> dynamicParameters = reader.getDynamicParameters();

            // Checking envelope equality
            if (!envelope.equals(this.envelope, DELTA, true)) {
                throw new IllegalArgumentException("The coverage envelope must be the same");
            }

            // Checking gridRange equality
            final Rectangle thisRectangle = new Rectangle(this.gridRange.getLow(0),
                    this.gridRange.getLow(1), this.gridRange.getSpan(0), this.gridRange.getSpan(1));
            final Rectangle thatRectangle = new Rectangle(gridRange.getLow(0), gridRange.getLow(1),
                    gridRange.getSpan(0), gridRange.getSpan(1));
            if (!thisRectangle.equals(thatRectangle)) {
                throw new IllegalArgumentException("The coverage gridRange should be the same");
            }

            // Checking dimensions
            if (metadataNames.length != this.metadataNames.length) {
                throw new IllegalArgumentException(
                        "The coverage metadataNames should have the same size");
            }

            final Set<String> metadataSet = new HashSet<String>(Arrays.asList(metadataNames));
            for (String metadataName : this.metadataNames) {
                if (!metadataSet.contains(metadataName)) {
                    throw new IllegalArgumentException("The coverage metadata are different");
                }
            }

            // TODO: Add check for dynamic parameters

            // Checking CRS
            MathTransform destinationToSourceTransform = null;
            if (!CRS.equalsIgnoreMetadata(crs, this.crs)) {
                try {
                    destinationToSourceTransform = CRS.findMathTransform(crs, this.crs, true);
                } catch (FactoryException e) {
                    throw new DataSourceException("Unable to inspect request CRS", e);
                }
            }

            // now transform the requested envelope to source crs
            if (destinationToSourceTransform != null && !destinationToSourceTransform.isIdentity()) {
                throw new IllegalArgumentException(
                        "The coverage coordinateReferenceSystem should be the same");
            }

            // Checking data type
            if (layout.getSampleModel(null).getDataType() != this.layout.getSampleModel(null)
                    .getDataType()) {
                throw new IllegalArgumentException("The coverage dataType should be the same");
            }

        }
    }

    /**
     * A simple reader which will apply coverages band customizations to the {@link CoverageView}
     */
    static class CoverageDimensionCustomizerViewReader extends CoverageDimensionCustomizerReader {

        public CoverageDimensionCustomizerViewReader(GridCoverage2DReader delegate,
                String coverageName, CoverageInfo info) {
            super(delegate, coverageName, info);
        }

        protected GridSampleDimension[] wrapDimensions(SampleDimension[] dims) {
            GridSampleDimension[] wrappedDims = null;
            CoverageInfo info = getCoverageInfo();
            if (info != null) {
                List<CoverageDimensionInfo> storedDimensions = info.getDimensions();
                MetadataMap map = info.getMetadata();
                if (map.containsKey(CoverageView.COVERAGE_VIEW)) {
                    CoverageView coverageView = (CoverageView) map
                            .get(CoverageView.COVERAGE_VIEW);
                    List<CoverageBand> coverageBands = coverageView.getBands(getCoverageName());
                    wrappedDims = (coverageBands != null && !coverageBands.isEmpty()) ? new GridSampleDimension[coverageBands.size()] : null;
                    int i = 0;
                    for (CoverageBand band : coverageBands) {
                        if (storedDimensions != null && storedDimensions.size() > 0) {
                            CoverageDimensionInfo dimensionInfo = storedDimensions.get(band.getIndex());
                            wrappedDims[i] = WrappedSampleDimension.build((GridSampleDimension) dims[i],
                                    dimensionInfo);
                        } else {
                            CoverageDimensionInfo dimensionInfo = new CoverageDimensionImpl();
                            dimensionInfo.setName(band.getDefinition());
                            wrappedDims[i] = WrappedSampleDimension.build((GridSampleDimension) dims[i],
                                    dimensionInfo);
                        }
                        i++;
                    }
                } else {
                    super.wrapDimensions(wrappedDims);
                }
            }
            return wrappedDims;
        }
    }

    static class CoverageDimensionCustomizerViewStructuredReader extends
            CoverageDimensionCustomizerViewReader {

        public CoverageDimensionCustomizerViewStructuredReader(GridCoverage2DReader delegate,
                String coverageName, CoverageInfo info) {
            super(delegate, coverageName, info);
        }

    }

    /** The CoverageView containing definition */
    CoverageView coverageView;

    /** The name of the reference coverage, we can remove/revisit it once we relax some constraint */
    String referenceName;

    private String coverageName;

    private GridCoverage2DReader delegate;

    private Hints hints;

    /** The CoverageInfo associated to the CoverageView */
    private CoverageInfo coverageInfo;

    private GridCoverageFactory coverageFactory;

    private ImageLayout imageLayout;

    public CoverageViewReader(GridCoverage2DReader delegate, CoverageView coverageView,
            CoverageInfo coverageInfo, Hints hints) {
        this.coverageName = coverageView.getName();
        this.delegate = delegate;
        this.coverageView = coverageView;
        this.coverageInfo = coverageInfo;
        this.hints = hints;
        // Refactor this once supporting heterogeneous elements
        referenceName = coverageView.getBand(0).getInputCoverageBands().get(0).getCoverageName();
        if (this.hints != null && this.hints.containsKey(Hints.GRID_COVERAGE_FACTORY)) {
            final Object factory = this.hints.get(Hints.GRID_COVERAGE_FACTORY);
            if (factory != null && factory instanceof GridCoverageFactory) {
                this.coverageFactory = (GridCoverageFactory) factory;
            }
        }
        if (this.coverageFactory == null) {
            this.coverageFactory = CoverageFactoryFinder.getGridCoverageFactory(this.hints);
        }
        ImageLayout layout;
        try {
            layout = delegate.getImageLayout(referenceName);
            List<CoverageBand> bands = coverageView.getCoverageBands();
            SampleModel originalSampleModel = layout.getSampleModel(null);
            SampleModel sampleModel = RasterFactory.createBandedSampleModel(originalSampleModel.getDataType(),
                    originalSampleModel.getWidth(), originalSampleModel.getHeight(), bands.size());

            ColorModel colorModel = ImageIOUtilities.createColorModel(sampleModel);
            this.imageLayout = new ImageLayout2(layout.getMinX(null), layout.getMinY(null), originalSampleModel.getWidth(), 
                    originalSampleModel.getHeight());
            imageLayout.setSampleModel(sampleModel);
            imageLayout.setColorModel(colorModel);
        } catch (IOException e) {
           throw new RuntimeException(e);
        }
    }

    @Override
    public GridCoverage2D read(GeneralParameterValue[] parameters) throws IllegalArgumentException,
            IOException {

        List<CoverageBand> bands = coverageView.getCoverageBands();
        List<GridCoverage2D> coverages = new ArrayList<GridCoverage2D>();
        
        CoveragesConsistencyChecker checker = null;
        
        int coverageBandsSize = bands.size();
        
        //Check params, populate band indices to read if BANDS param has been defined
        ArrayList<Integer> selectedBandIndices = new ArrayList<Integer>();
        for (int m=0;m<coverageBandsSize;m++)
            selectedBandIndices.add(m);
        
        if (parameters != null) {
            for (int i = 0; i < parameters.length; i++) {
                final ParameterValue param = (ParameterValue) parameters[i];
                if (param.getDescriptor().equals(AbstractGridFormat.BANDS)) {
                    int[] bandIndicesParam = (int[]) param.getValue();
                    if (bandIndicesParam != null) {
                        selectedBandIndices = new ArrayList<Integer>();
                        for (int bIdx = 0; bIdx < bandIndicesParam.length; bIdx++) {
                            selectedBandIndices.add(bandIndicesParam[bIdx]);
                        }
                        break;
                    }
                }
            }
        }
        
        // Since composition of a raster band using a formula applied on individual bands has not
        // been implemented, the normal case is that each CoverageBand is in fact a single band from
        // an input coverage. When band composition will be implemented, this will be the point where
        // band composition will occur, before the final BandSelect/BandMerge ops

        // This is a good spot to read coverages. Reading a coverage is done only once, it is
        // cached to be used for its other bands that possibly take part in the CoverageView definition
        HashMap<String, GridCoverage2D> inputCoverages = new HashMap<String, GridCoverage2D>();
        for (int bIdx : selectedBandIndices) {
            CoverageBand band = bands.get(bIdx);
            List<InputCoverageBand> selectedBands = band.getInputCoverageBands();

            // Peek for coverage name
            String coverageName = selectedBands.get(0).getCoverageName();
            if (!inputCoverages.containsKey(coverageName)) {
                GridCoverage2DReader reader = wrap(delegate, coverageName, coverageInfo);
                // Remove this when removing constraints
                if (checker == null) {
                    checker = new CoveragesConsistencyChecker(reader);
                } else {
                    checker.checkConsistency(reader);
                }
                inputCoverages.put(coverageName, delegate.read(coverageName, parameters));
            }
        }
        
        // Group together bands that come from the same coverage

        ArrayList<CoverageBand> mergedBands = new ArrayList<CoverageBand>();

        int idx = 0;
        CoverageBand mBand = null;
        while (idx < selectedBandIndices.size()) {

            if (mBand == null) {
                // Create a temporary CoverageBand, to use later for SelectSampleDimension operations
                mBand = new CoverageBand();
                mBand.setInputCoverageBands(
                        bands.get(selectedBandIndices.get(idx)).getInputCoverageBands());
            }

            // peek to the next band. Is it from the same coverage?
            String coverageName = bands.get(selectedBandIndices.get(idx)).getInputCoverageBands()
                    .get(0).getCoverageName();

            if (idx + 1 < selectedBandIndices.size() && bands.get(selectedBandIndices.get(idx + 1))
                    .getInputCoverageBands().get(0).getCoverageName().equals(coverageName)) {
                // Same coverage, add its bands to the previous
                ArrayList<InputCoverageBand> groupBands = new ArrayList<InputCoverageBand>();
                groupBands.addAll(mBand.getInputCoverageBands());
                groupBands.addAll(
                        bands.get(selectedBandIndices.get(idx + 1)).getInputCoverageBands());
                mBand.setInputCoverageBands(groupBands);
            } else {
                mergedBands.add(mBand);
                mBand = null;
            }
            idx++;
        }
        
        for (CoverageBand band:mergedBands){ 
            List<InputCoverageBand> selectedBands = band.getInputCoverageBands();
            
            // Peek for coverage name
            String coverageName = selectedBands.get(0).getCoverageName();
            
            // Get band indices for band selection
            ArrayList<Integer> bandIndices = new ArrayList<Integer>(selectedBands.size());
            for (InputCoverageBand icb:selectedBands){
                bandIndices.add(Integer.parseInt(icb.getBand()));
            }

            GridCoverage2D coverage = inputCoverages.get(coverageName);
            if (coverage != null) {
                final ParameterValueGroup param = PROCESSOR.getOperation("SelectSampleDimension").getParameters();
                param.parameter("Source").setValue(coverage);
                param.parameter("SampleDimensions").setValue( ArrayUtils.toPrimitive(bandIndices.toArray(new Integer[bandIndices.size()])));
                coverage = (GridCoverage2D) PROCESSOR.doOperation(param, hints);
                coverages.add(coverage);
            }
        }

        if (coverages.isEmpty()) {
            return null;
        }
        GridCoverage2D sampleCoverage = coverages.get(0);

        RenderedImage image = null;
        Map properties = null;
        if (coverages.size() > 1) {
                final ParameterValueGroup param = PROCESSOR.getOperation("BandMerge").getParameters();
                param.parameter("sources").setValue(coverages);
                sampleCoverage = (GridCoverage2D) PROCESSOR.doOperation(param, hints);
        }
        properties = sampleCoverage.getProperties();
        image = sampleCoverage.getRenderedImage();

        GridSampleDimension[] wrappedDims = new GridSampleDimension[sampleCoverage
                .getNumSampleDimensions()];

        List<CoverageDimensionInfo> storedDimensions = coverageInfo.getDimensions();
        MetadataMap map = coverageInfo.getMetadata();
        if (map.containsKey(CoverageView.COVERAGE_VIEW)) {
            CoverageView coverageView = (CoverageView) map.get(CoverageView.COVERAGE_VIEW);
            wrappedDims = (selectedBandIndices != null && !selectedBandIndices.isEmpty())
                    ? new GridSampleDimension[selectedBandIndices.size()] : null;
            for (int i : selectedBandIndices) {
                CoverageBand band = coverageView.getBand(i);
                CoverageDimensionInfo dimensionInfo = new CoverageDimensionImpl();
                dimensionInfo.setName(band.getDefinition());
                if (storedDimensions != null && storedDimensions.size() > 0)
                    dimensionInfo = storedDimensions.get(band.getIndex());
                wrappedDims[i] = WrappedSampleDimension.build(sampleCoverage.getSampleDimension(i),
                        dimensionInfo);
            }
        }

        GridCoverage2D mergedCoverage = coverageFactory.create(coverageInfo.getName(), image,
                sampleCoverage.getGridGeometry(), wrappedDims, null, properties);

        return mergedCoverage;
    }


    /**
     * @param coverageName
     */
    protected void checkCoverageName(String coverageName) {
        if (!this.coverageName.equalsIgnoreCase(coverageName)) {
            throw new IllegalArgumentException("The specified coverageName isn't the one of this coverageView");
        }
    }

    public void dispose() throws IOException {
        delegate.dispose();
    }

    /**
     * Get a {@link GridCoverage2DReader} wrapping the provided delegate reader
     */
    private static GridCoverage2DReader wrap(GridCoverage2DReader delegate, String coverageName,
            CoverageInfo info) {
        GridCoverage2DReader reader = delegate;
        if (coverageName != null) {
            reader = SingleGridCoverage2DReader.wrap(delegate, coverageName);
        }
        if (reader instanceof StructuredGridCoverage2DReader) {
            return new CoverageDimensionCustomizerViewStructuredReader(
                    reader, coverageName, info);
        } else {
            return new CoverageDimensionCustomizerViewReader(reader, coverageName, info);
        }
    }

    /**
     * Get a {@link GridCoverage2DReader} wrapping the provided delegate reader
     */
    public static GridCoverage2DReader wrap(GridCoverage2DReader reader, CoverageView coverageView,
            CoverageInfo coverageInfo, Hints hints) {
        if (reader instanceof StructuredGridCoverage2DReader) {
            return new StructuredCoverageViewReader((StructuredGridCoverage2DReader) reader,
                    coverageView, coverageInfo, hints);
        } else {
            return new CoverageViewReader(reader, coverageView,
                    coverageInfo, hints);
        }
    }

    @Override
    public Format getFormat() {
        return new Format(){

            private final Format delegateFormat = delegate.getFormat();

            @Override
            public ParameterValueGroup getWriteParameters() {
                return delegateFormat.getWriteParameters();
            }
            
            @Override
            public String getVersion() {
                return delegateFormat.getVersion();
            }
            
            @Override
            public String getVendor() {
                return delegateFormat.getVendor();
            }
            
            @Override
            public ParameterValueGroup getReadParameters() {
                HashMap<String, String> info = new HashMap<String, String>();

                info.put("name", getName());
                info.put("description", getDescription());
                info.put("vendor", getVendor());
                info.put("docURL", getDocURL());
                info.put("version", getVersion());
                
                List<GeneralParameterDescriptor> delegateFormatParams 
                    = new ArrayList<GeneralParameterDescriptor>();
                delegateFormatParams.addAll(
                        delegateFormat.getReadParameters().getDescriptor().descriptors());
                delegateFormatParams.add(AbstractGridFormat.BANDS);
                
                return new ParameterGroup(new DefaultParameterDescriptorGroup(
                        info,
                        delegateFormatParams.toArray(
                                new GeneralParameterDescriptor[delegateFormatParams.size()])));
            }
            
            @Override
            public String getName() {
                return delegateFormat.getName();
            }
            
            @Override
            public String getDocURL() {
                return delegateFormat.getDocURL();
            }
            
            @Override
            public String getDescription() {
                return delegateFormat.getDescription();
            }

        };
    }

    @Override
    public Object getSource() {
        return delegate.getSource();
    }

    @Override
    public String[] getMetadataNames(String coverageName) throws IOException {
        checkCoverageName(coverageName);
        return delegate.getMetadataNames(referenceName);
    }

    @Override
    public String getMetadataValue(String coverageName, String name) throws IOException {
        checkCoverageName(coverageName);
        return delegate.getMetadataValue(referenceName, name);
    }

    @Override
    public String[] listSubNames() throws IOException {
        return delegate.listSubNames();
    }

    @Override
    public String[] getGridCoverageNames() throws IOException {
        return delegate.getGridCoverageNames();
    }

    @Override
    public int getGridCoverageCount() throws IOException {
        return delegate.getGridCoverageCount();
    }

    @Override
    public String getCurrentSubname() throws IOException {
        return delegate.getCurrentSubname();
    }

    @Override
    public boolean hasMoreGridCoverages() throws IOException {
        return delegate.hasMoreGridCoverages();
    }

    @Override
    public void skip() throws IOException {
        delegate.skip();
        
    }

    @Override
    public GeneralEnvelope getOriginalEnvelope(String coverageName) {
        checkCoverageName(coverageName);
        return delegate.getOriginalEnvelope(referenceName);
    }

    @Override
    public CoordinateReferenceSystem getCoordinateReferenceSystem(String coverageName) {
        checkCoverageName(coverageName);
        return delegate.getCoordinateReferenceSystem(referenceName);
    }

    @Override
    public GridEnvelope getOriginalGridRange(String coverageName) {
        checkCoverageName(coverageName);
        return delegate.getOriginalGridRange(referenceName);
    }

    @Override
    public MathTransform getOriginalGridToWorld(String coverageName, PixelInCell pixInCell) {
        checkCoverageName(coverageName);
        return delegate.getOriginalGridToWorld(referenceName, pixInCell);
    }

    @Override
    public GridCoverage2D read(String coverageName, GeneralParameterValue[] parameters)
            throws IOException {
        checkCoverageName(coverageName);
        return read(parameters);
    }

    @Override
    public Set<ParameterDescriptor<List>> getDynamicParameters(String coverageName)
            throws IOException {
        checkCoverageName(coverageName);
        return delegate.getDynamicParameters(referenceName);
    }

    @Override
    public double[] getReadingResolutions(String coverageName, OverviewPolicy policy,
            double[] requestedResolution) throws IOException {
        checkCoverageName(coverageName);
        return delegate.getReadingResolutions(referenceName, policy, requestedResolution);
    }

    @Override
    public int getNumOverviews(String coverageName) {
        checkCoverageName(coverageName);
        return delegate.getNumOverviews(referenceName);
    }

    @Override
    public ImageLayout getImageLayout() throws IOException {
        return imageLayout;
    }

    @Override
    public ImageLayout getImageLayout(String coverageName) throws IOException {
        checkCoverageName(coverageName);
        return imageLayout;
    }

    @Override
    public double[][] getResolutionLevels(String coverageName) throws IOException {
        checkCoverageName(coverageName);
        return delegate.getResolutionLevels(referenceName);
    }

    @Override
    public String[] getMetadataNames() throws IOException {
        return delegate.getMetadataNames(referenceName);
    }

    @Override
    public String getMetadataValue(String name) throws IOException {
        return delegate.getMetadataValue(referenceName, name);
    }

    @Override
    public GeneralEnvelope getOriginalEnvelope() {
        return delegate.getOriginalEnvelope(referenceName);
    }

    @Override
    public GridEnvelope getOriginalGridRange() {
        return delegate.getOriginalGridRange(referenceName);
    }

    @Override
    public MathTransform getOriginalGridToWorld(PixelInCell pixInCell) {
        return delegate.getOriginalGridToWorld(referenceName, pixInCell);
    }

    @Override
    public CoordinateReferenceSystem getCoordinateReferenceSystem() {
        return delegate.getCoordinateReferenceSystem(referenceName);
    }

    @Override
    public Set<ParameterDescriptor<List>> getDynamicParameters() throws IOException {
        return delegate.getDynamicParameters(referenceName);
    }

    @Override
    public double[] getReadingResolutions(OverviewPolicy policy, double[] requestedResolution)
            throws IOException {
        return delegate.getReadingResolutions(referenceName, policy, requestedResolution);
    }

    @Override
    public int getNumOverviews() {
        return delegate.getNumOverviews(referenceName);
    }

    @Override
    public double[][] getResolutionLevels() throws IOException {
        return delegate.getResolutionLevels(referenceName);
    }

    @Override
    public DatasetLayout getDatasetLayout() {
        return delegate.getDatasetLayout();
    }

    @Override
    public DatasetLayout getDatasetLayout(String coverageName) {
        return delegate.getDatasetLayout(coverageName);
    }

    @Override
    public ServiceInfo getInfo() {
        return delegate.getInfo();
    }

    @Override
    public ResourceInfo getInfo(String coverageName) {
        return delegate.getInfo(coverageName);
    }
}
