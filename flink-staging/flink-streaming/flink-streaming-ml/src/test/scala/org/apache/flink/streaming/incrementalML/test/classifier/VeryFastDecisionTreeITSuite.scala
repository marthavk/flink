/*
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
package org.apache.flink.streaming.incrementalML.test.classifier

import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.incrementalML.classifier.VeryFastDecisionTree
import org.apache.flink.test.util.FlinkTestBase
import org.scalatest.{FlatSpec, Matchers}

class VeryFastDecisionTreeITSuite
  extends FlatSpec
  with Matchers
  with FlinkTestBase {

  behavior of "Flink's Very Fast Decision tree algorithm"

  import VeryFastDecisionTreeData._

  it should "Create the classification HT of the given data set" in {

    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val vfdt = VeryFastDecisionTree(env)
    vfdt.fit(env.fromCollection(data))
    //    println(con.getExecutionPlan())
    env.execute()
  }
}

object VeryFastDecisionTreeData {

  val data: Seq[(Double, List[Any])] = List(
    (0.0, List("Rainy", "Hot", "High", 4.00, -7.0, 0.0)),
    (0.0, List("Rainy", "Hot", "High", 4.00, -12.0, 0.0)),
    (1.0, List("Overcast", "Hot", "High", -4.00, -3.0, 0.0)),
    (1.0, List("Sunny", "Mild", "High", -3.00, -12.0, 0.0)),
    (1.0, List("Sunny", "Cool", "Normal", -4.00, -12.0, 0.0)),
    (0.0, List("Sunny", "Cool", "Normal", 4.00, -12.0, 0.0)),
    (1.0, List("Overcast", "Cool", "Normal", -2.00, -12.0, 0.0)),
    (0.0, List("Rainy", "Mild", "High", 4.00, -12.0, 0.0)),
    (1.0, List("Rainy", "Cool", "Normal", -4.00, -12.0, 0.0)),
    (1.0, List("Sunny", "Mild", "Normal", -3.00, -12.0, 0.0)),
    (1.0, List("Rainy", "Mild", "Normal", -4.00, -12.0, 0.0)),
    (1.0, List("Overcast", "Mild", "High", -2.00, -12.0, 0.0)),
    (1.0, List("Overcast", "Hot", "Normal", -4.00, -12.0, 0.0)),
    (0.0, List("Sunny", "Mild", "High", 4.00, -12.0, 0.0))
  )
}