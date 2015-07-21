package org.apache.flink.streaming.sampling.examples;/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import breeze.stats.distributions.Gaussian;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.sampling.generators.GaussianDistribution;
import org.apache.flink.streaming.sampling.samplers.StreamSampler;
import org.apache.flink.streaming.sampling.samplers.UniformSampler;
import org.apache.flink.streaming.sampling.sources.DebugSource;
import org.apache.flink.streaming.sampling.sources.NormalStreamSource;

/**
 * Created by marthavk on 2015-06-20.
 */
public class Foo {

	public static void main(String[] args) throws Exception {
		StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
		env.setParallelism(1);
		//String file = "/home/marthavk/Desktop/thesis-all-docs/resources/dataSets/randomRBF/randomRBF-10M.arff";
		//long end = 1000000000;
		//DataStreamSource<Long> source = env.addSource(new DebugSource(end));
		//DataStreamSource<GaussianDistribution> source = env.addSource(new NormalStreamSource());
		DataStreamSource<GaussianDistribution> src = env.addSource(new NormalStreamSource());
		/*

		UniformSampler<Long> reservoir = new UniformSampler<Long>(1000000, 100);
		SingleOutputStreamOperator<Long, ?> datapoints = source.transform("sample", source.getType(), new StreamSampler<Long>(reservoir));
		datapoints.print();
		//source.count().print();*/
		env.execute();
/*
		//DataStreamSource<String> source = env.readTextFile("/media/marthavk/PerseusFiles/results/classification_results/rbf_RS1K100");
		source.map(new MapFunction<String, String>() {
			@Override
			public String map(String value) throws Exception {
				String[] v = value.split(",");
				return v[2];
			}
		}).writeAsText("/home/marthavk/workspace/flink/flink-staging/flink-streaming/flink-streaming-ml/src/main/resources/rbf_RS1K100_error");
		//source.print();

		env.execute();
*/

		//.readFile("/media/marthavk/PerseusFiles/results/classification_results/rbf_BR1K100");
	}
}