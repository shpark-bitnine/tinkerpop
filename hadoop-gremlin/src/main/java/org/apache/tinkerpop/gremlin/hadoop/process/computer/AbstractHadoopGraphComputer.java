/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tinkerpop.gremlin.hadoop.process.computer;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.tinkerpop.gremlin.hadoop.Constants;
import org.apache.tinkerpop.gremlin.hadoop.structure.HadoopGraph;
import org.apache.tinkerpop.gremlin.hadoop.structure.util.ConfUtil;
import org.apache.tinkerpop.gremlin.process.computer.GraphComputer;
import org.apache.tinkerpop.gremlin.process.computer.GraphFilter;
import org.apache.tinkerpop.gremlin.process.computer.MapReduce;
import org.apache.tinkerpop.gremlin.process.computer.VertexProgram;
import org.apache.tinkerpop.gremlin.process.computer.util.GraphComputerHelper;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;
import org.apache.tinkerpop.gremlin.util.Gremlin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public abstract class AbstractHadoopGraphComputer implements GraphComputer {

    protected final Logger logger;
    protected final HadoopGraph hadoopGraph;
    protected boolean executed = false;
    protected final Set<MapReduce> mapReducers = new HashSet<>();
    protected VertexProgram<Object> vertexProgram;
    protected int workers = 1;

    protected ResultGraph resultGraph = null;
    protected Persist persist = null;

    protected GraphFilter graphFilter = new GraphFilter();

    public AbstractHadoopGraphComputer(final HadoopGraph hadoopGraph) {
        this.hadoopGraph = hadoopGraph;
        this.logger = LoggerFactory.getLogger(this.getClass());
    }

    @Override
    public GraphComputer vertices(final Traversal<Vertex, Vertex> vertexFilter) {
        this.graphFilter.setVertexFilter(vertexFilter);
        return this;
    }

    @Override
    public GraphComputer edges(final Traversal<Vertex, Edge> edgeFilter) {
        this.graphFilter.setEdgeFilter(edgeFilter);
        return this;
    }

    @Override
    public GraphComputer result(final ResultGraph resultGraph) {
        this.resultGraph = resultGraph;
        return this;
    }

    @Override
    public GraphComputer persist(final Persist persist) {
        this.persist = persist;
        return this;
    }

    @Override
    public GraphComputer program(final VertexProgram vertexProgram) {
        this.vertexProgram = vertexProgram;
        return this;
    }

    @Override
    public GraphComputer mapReduce(final MapReduce mapReduce) {
        this.mapReducers.add(mapReduce);
        return this;
    }

    @Override
    public GraphComputer workers(final int workers) {
        this.workers = workers;
        return this;
    }

    @Override
    public String toString() {
        return StringFactory.graphComputerString(this);
    }

    protected void validateStatePriorToExecution() {
        // a graph computer can only be executed one time
        if (this.executed)
            throw Exceptions.computerHasAlreadyBeenSubmittedAVertexProgram();
        else
            this.executed = true;
        // it is not possible execute a computer if it has no vertex program nor mapreducers
        if (null == this.vertexProgram && this.mapReducers.isEmpty())
            throw GraphComputer.Exceptions.computerHasNoVertexProgramNorMapReducers();
        // it is possible to run mapreducers without a vertex program
        if (null != this.vertexProgram) {
            GraphComputerHelper.validateProgramOnComputer(this, vertexProgram);
            this.mapReducers.addAll(this.vertexProgram.getMapReducers());
        }
        // if the user didn't set desired persistence/resultgraph, then get from vertex program or else, no persistence
        this.persist = GraphComputerHelper.getPersistState(Optional.ofNullable(this.vertexProgram), Optional.ofNullable(this.persist));
        this.resultGraph = GraphComputerHelper.getResultGraphState(Optional.ofNullable(this.vertexProgram), Optional.ofNullable(this.resultGraph));
        // determine persistence and result graph options
        if (!this.features().supportsResultGraphPersistCombination(this.resultGraph, this.persist))
            throw GraphComputer.Exceptions.resultGraphPersistCombinationNotSupported(this.resultGraph, this.persist);
        // if too many workers are requested, throw appropriate exception
        if (this.workers > this.features().getMaxWorkers())
            throw GraphComputer.Exceptions.computerRequiresMoreWorkersThanSupported(this.workers, this.features().getMaxWorkers());
    }

    @Override
    public Features features() {
        return new Features();
    }

    public class Features implements GraphComputer.Features {

        @Override
        public boolean supportsVertexAddition() {
            return false;
        }

        @Override
        public boolean supportsVertexRemoval() {
            return false;
        }

        @Override
        public boolean supportsVertexPropertyRemoval() {
            return false;
        }

        @Override
        public boolean supportsEdgeAddition() {
            return false;
        }

        @Override
        public boolean supportsEdgeRemoval() {
            return false;
        }

        @Override
        public boolean supportsEdgePropertyAddition() {
            return false;
        }

        @Override
        public boolean supportsEdgePropertyRemoval() {
            return false;
        }

        @Override
        public boolean supportsResultGraphPersistCombination(final ResultGraph resultGraph, final Persist persist) {
            if (hadoopGraph.configuration().containsKey(Constants.GREMLIN_HADOOP_GRAPH_WRITER)) {
                final Object writer = ReflectionUtils.newInstance(hadoopGraph.configuration().getGraphWriter(), ConfUtil.makeHadoopConfiguration(hadoopGraph.configuration()));
                if (writer instanceof PersistResultGraphAware)
                    return ((PersistResultGraphAware) writer).supportsResultGraphPersistCombination(resultGraph, persist);
                else {
                    logger.warn(writer.getClass() + " does not implement " + PersistResultGraphAware.class.getSimpleName() + " and thus, persistence options are unknown -- assuming all options are possible");
                    return true;
                }
            } else {
                logger.warn("No " + Constants.GREMLIN_HADOOP_GRAPH_WRITER + " property provided and thus, persistence options are unknown -- assuming all options are possible");
                return true;
            }
        }

        @Override
        public boolean supportsDirectObjects() {
            return false;
        }
    }

    //////////

    public static File copyDirectoryIfNonExistent(final FileSystem fileSystem, final String localDirectory) {
        try {
            final String hadoopGremlinLibsRemote = "hadoop-gremlin-" + Gremlin.version() + "-libs";
            File file = new File(localDirectory);
            if ((Boolean.valueOf(System.getProperty("is.testing", "false")) || !file.exists()) && fileSystem.exists(new Path(localDirectory)) && fileSystem.isDirectory(new Path(localDirectory))) {
                final File tempDirectory = new File(System.getProperty("java.io.tmpdir") + "/" + hadoopGremlinLibsRemote);
                if (!tempDirectory.exists()) assert tempDirectory.mkdir();
                final String tempPath = tempDirectory.getAbsolutePath() + "/" + new File(localDirectory).getName();
                final RemoteIterator<LocatedFileStatus> files = fileSystem.listFiles(new Path(localDirectory), false);
                while (files.hasNext()) {
                    final LocatedFileStatus f = files.next();
                    fileSystem.copyToLocalFile(f.getPath(), new Path(tempPath + "/" + f.getPath().getName()));
                }
                return new File(tempPath);
            } else
                return file;
        } catch (final IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }
}
