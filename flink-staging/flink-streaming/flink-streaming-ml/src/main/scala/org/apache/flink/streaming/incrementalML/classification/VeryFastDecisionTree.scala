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
package org.apache.flink.streaming.incrementalML.classification

import java.lang.Iterable
import java.util

import org.apache.flink.api.common.functions.{FilterFunction, FlatMapFunction}
import org.apache.flink.ml.common.{LabeledVector, Parameter, ParameterMap}
import org.apache.flink.streaming.api.collector.selector.OutputSelector
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.incrementalML.attributeObserver.{AttributeObserver,
NominalAttributeObserver, NumericalAttributeObserver}
import org.apache.flink.streaming.incrementalML.classification.Metrics._
import org.apache.flink.streaming.incrementalML.classification.VeryFastDecisionTree._
import org.apache.flink.streaming.incrementalML.common.{Learner, Utils}
import org.apache.flink.util.Collector

import scala.collection.mutable

/**
 *
 * @param context
 */
class VeryFastDecisionTree(
  context: StreamExecutionEnvironment)
  extends Learner[LabeledVector, (Int, Metrics)]
  with Serializable {

  //TODO:: Check what other parameters need to be set
  def setMinNumberOfInstances(minInstances: Int): VeryFastDecisionTree = {
    parameters.add(MinNumberOfInstances, minInstances)
    this
  }

  def setVfdtDelta(delta: Double): VeryFastDecisionTree = {
    parameters.add(VfdtDelta, delta)
    this
  }

  def setVfdtTau(tau: Double): VeryFastDecisionTree = {
    parameters.add(VfdtTau, tau)
    this
  }

  def setNominalAttributes(noNominalAttrs: Map[Int, Int]): VeryFastDecisionTree = {
    parameters.add(NominalAttributes, noNominalAttrs)
    this
  }

  def setNumberOfClasses(noClasses: Int): VeryFastDecisionTree = {
    parameters.add(NumberOfClasses, noClasses)
    this
  }

  private def iterationFunction(dataPointsStream: DataStream[Metrics],
    resultingParameters: ParameterMap): (DataStream[Metrics],
    DataStream[Metrics], DataStream[(Int, Metrics)]) = {

    val mSAds = dataPointsStream.flatMap(new GlobalModelMapper(resultingParameters))
      .setParallelism(1)

    //TODO:: Decide which values will declare if it is a Model or a Signal
    val attributes = mSAds.filter(new FilterFunction[(Int, Metrics)] {
      override def filter(value: (Int, Metrics)): Boolean = {
        return value._1 >= 0
      }
    }).setParallelism(1)

    val modelAndSignal = mSAds.filter(new FilterFunction[(Int, Metrics)] {
      override def filter(value: (Int, Metrics)): Boolean = {
        return (value._1 == -2) //metric or Signal
      }
    }).setParallelism(1)

    val classificationPerInstance = mSAds.filter(new FilterFunction[(Int, Metrics)] {
      override def filter(value: (Int, Metrics)): Boolean = {
        return (value._1 == -3) //InstanceClassification
      }
    }).setParallelism(1)
    //    classificationPerInstance.print()

    val splitDs = attributes.groupBy(0).merge(modelAndSignal.broadcast)
      .flatMap(new PartialVFDTMetricsMapper).setParallelism(1).split(new OutputSelector[Metrics] {
      override def select(value: Metrics): Iterable[String] = {
        val output = new util.ArrayList[String]()

        value match {
          case _: EvaluationMetric =>
            output.add("feedback")
          case _ =>
            output.add("output")
        }
        output
      }
    })

    val feedback: DataStream[Metrics] = splitDs.select("feedback")
    val output: DataStream[Metrics] = splitDs.select("output")
    (feedback, output, classificationPerInstance)
  }

  override def fit(input: DataStream[LabeledVector], fitParameters: ParameterMap):
  DataStream[(Int, Metrics)] = {
    val resultingParameters = this.parameters ++ fitParameters

    val dataPointsStream: DataStream[Metrics] = input.map(dp => DataPoints(dp))

    var prequentialEvaluation: DataStream[(Int, Metrics)] = null

    val out = dataPointsStream.iterate[Metrics](10000)(dataPointsStream => {
      val (feedback, output, preqEvalStream) = iterationFunction(dataPointsStream,
        resultingParameters)
      prequentialEvaluation = preqEvalStream
      (feedback, output)
    })
    prequentialEvaluation
  }
}

object VeryFastDecisionTree {

  /** Minimum number of instances seen, before deciding the new splitting feature.
    *
    */
  case object MinNumberOfInstances extends Parameter[Int] {
    override val defaultValue: Option[Int] = Some(200)
  }

  /** Hoeffding Bound tau parameter
    *
    */
  case object VfdtTau extends Parameter[Double] {
    override val defaultValue: Option[Double] = Some(0.05)
  }

  /** Hoeffding Bound delta parameter
    *
    */
  case object VfdtDelta extends Parameter[Double] {
    override val defaultValue: Option[Double] = Some(0.0000001)
  }

  /** Map that specifies which attributes are Nominal and how many possible values they will have
    *
    */
  case object NominalAttributes extends Parameter[Map[Int, Int]] {
    override val defaultValue: Option[Map[Int, Int]] = None
  }

  /**
   * Specifies the number of classes that the problem to be solved will have
   */
  case object NumberOfClasses extends Parameter[Int] {
    override val defaultValue: Option[Int] = Some(2)
  }

  def apply(context: StreamExecutionEnvironment): VeryFastDecisionTree = {
    new VeryFastDecisionTree(context)
  }
}


/** This Flat Mapper can take as input any value of  Metrics type.
  * Specifically the input values can be:
  * inType1: [[DataPoints]] -> Is a wrapper for a [[LabeledVector]] value
  * inType2: [[EvaluationMetric]] -> Contains, per node, at most two attributes that will give the
  * maximum Information Gain.
  *
  * This mapper emits tuples of (attributeId,valueToBeSent), where the identifier is used for
  * filtering the different types of [[Metrics]] that the valueToBeSent can be.
  *
  * The (identifier,valueToBeSent) can have any of the following values
  * outType1: (Int,VFDTAttributes) -> when an attribute has been emitted
  * outType2: (-2,CalculateMetricsSignal) -> When a signal for splitting a leaf is sent
  *
  */
class GlobalModelMapper(resultingParameters: ParameterMap)
  extends FlatMapFunction[Metrics, (Int, Metrics)] {

  //counterPerLeaf -> (leafId,(#0,#1,#2))
  var counterPerLeaf: mutable.Map[Int, (Int, Int, Int)] = mutable.HashMap[Int, (Int, Int, Int)](
    (0, (0, 0, 0)))

  //Create the root of the DecisionTreeModel
  val VFDT = DecisionTreeModel
  VFDT.createRootOfTheTree

  override def flatMap(value: Metrics, out: Collector[(Int, Metrics)]): Unit = {
    var leafId = 0
    value match {
      case newDataPoint: DataPoints => {
        //data point is received

        val featuresVector = newDataPoint.getFeatures

        //classify data point first
        leafId = VFDT.classifyDataPointToLeaf(featuresVector)
        val label = VFDT.getNodeLabel(leafId)
        if (!label.isNaN) {
          out.collect((-3, InstanceClassification(label, newDataPoint.getLabel)))
        }

        //TODO:: 2. update total distribution of each leaf (#Yes, #No) for calculating the
        // information gain -> is not need, we will just select the attribute with the smallest
        // entropy, which as a result will have the highest Information gain

        val temp = counterPerLeaf.getOrElseUpdate(leafId, (0, 0, 0))
        newDataPoint.getLabel match {
          case 0.0 =>
            counterPerLeaf = counterPerLeaf.updated(leafId, (temp._1 + 1, temp._2, temp._3))
          case 1.0 =>
            counterPerLeaf = counterPerLeaf.updated(leafId, (temp._1, temp._2 + 1, temp._3))
          case 2.0 =>
            counterPerLeaf = counterPerLeaf.updated(leafId, (temp._1, temp._2, temp._3 + 1))
          case _ =>
            throw new RuntimeException(s"I am sorry there was some problem with that class label:" +
              s" ${newDataPoint.getLabel}")
        }
        //#0 > #1
        //------------------------------------majority vote------------------------------------
        var leafLabel = 0.0
        if (counterPerLeaf(leafId)._1 < counterPerLeaf(leafId)._2) {
          leafLabel = 1.0
          if (counterPerLeaf(leafId)._2 < counterPerLeaf(leafId)._3) {
            leafLabel = 2.0
          }
        }
        else if (counterPerLeaf(leafId)._1 < counterPerLeaf(leafId)._3) {
          leafLabel = 2.0
        }
        VFDT.setNodeLabel(leafId, leafLabel) //majority vote for leaf label
        //------------------------------------end majority vote------------------------------------

        //TODO:: change this piece of code
        val nominal = resultingParameters.get(NominalAttributes)
        nominal match {
          case None => {
            for (i <- 0 until featuresVector.size) {
              //emit numerical attribute
              out.collect((i, VFDTAttributes(i, featuresVector(i), newDataPoint.getLabel, -1,
                leafId, AttributeType.Numerical)))
            }
          }
          case _ => {
            for (i <- 0 until featuresVector.size) {
              nominal.get.getOrElse(i, None) match {
                case nOfValue: Int => {
                  //emit Nominal attribute
                  out.collect((i, VFDTAttributes(i, featuresVector(i), newDataPoint.getLabel,
                    nOfValue, leafId, AttributeType.Nominal)))
                }
                case None => {
                  //emit numerical attribute
                  out.collect((i, VFDTAttributes(i, featuresVector(i), newDataPoint.getLabel, -1,
                    leafId, AttributeType.Numerical)))
                }
              }
            }
          }
        }
        //----------------------till here----------------------------------------------------------

        counterPerLeaf.getOrElse(leafId, None) match {
          case leafMetrics: (Int, Int, Int) => {
            //if we have seen at least MinNumberOfInstances and are not all of the same class
            if (((leafMetrics._1 + leafMetrics._2 + leafMetrics._3) % resultingParameters.get
              (MinNumberOfInstances).get == 0) &&
              (leafMetrics._1 * leafMetrics._2 != 0 || leafMetrics._2 * leafMetrics._3 != 0 ||
                leafMetrics._1 * leafMetrics._3 != 0)) {

              out.collect((-2, CalculateMetricsSignal(leafId, false)))
            }
          }
          case None =>
            throw new RuntimeException(s"------ 1 -----leaf:$leafId doesn't exist")
        }

      }
      case evaluationMetric: EvaluationMetric => {
        //metrics are received, then update global model
        //TODO:: Aggregate metrics and update global model. Do NOT broadcast global model

        val nonSplitEntro = nonSplittingEntropy(counterPerLeaf.get(evaluationMetric.leafId).get)

        val bestInfoGain = nonSplitEntro - evaluationMetric.bestValue._2._1
        val secondBestInfoGain = nonSplitEntro - evaluationMetric.secondBestValue._2._1
        println(s"bestValue: ${evaluationMetric.bestValue._2._1}, 2ndBest: " +
          s"${evaluationMetric.secondBestValue._2._1},  bestInfoGain: $bestInfoGain,  " +
          s"secondBestInfoGain: $secondBestInfoGain, " +
          s" bestInfoGain-secondBestInfoGain: ${bestInfoGain - secondBestInfoGain}, " +
          s"---nonSplitEntro: $nonSplitEntro")

        val hoeffdingBoundVariable = hoeffdingBound(counterPerLeaf.get(evaluationMetric.leafId)
          .get._1 + counterPerLeaf.get(evaluationMetric.leafId).get._2 + counterPerLeaf.get
          (evaluationMetric.leafId).get._3)

        if (((bestInfoGain - secondBestInfoGain > hoeffdingBoundVariable) &&
          bestInfoGain >= nonSplitEntro) || (
          (bestInfoGain - secondBestInfoGain < hoeffdingBoundVariable) && (
            hoeffdingBoundVariable < resultingParameters.get(VfdtTau).get))) {

          val nominal = resultingParameters.get(NominalAttributes)
          nominal match {
            case None => {
              VFDT.growTree(evaluationMetric.leafId, evaluationMetric.bestValue._1,
                AttributeType.Numerical, evaluationMetric.bestValue._2._2,
                evaluationMetric.bestValue._2._1)
            }
            case _ => {
              nominal.get.getOrElse(evaluationMetric.bestValue._1, None) match {
                case None => {
                  VFDT.growTree(evaluationMetric.leafId, evaluationMetric.bestValue._1,
                    AttributeType.Numerical, evaluationMetric.bestValue._2._2,
                    evaluationMetric.bestValue._2._1)
                  out.collect((-2, CalculateMetricsSignal(leafId, true)))
                }
                case x: Int => {
                  VFDT.growTree(evaluationMetric.leafId, evaluationMetric.bestValue._1,
                    AttributeType.Nominal, evaluationMetric.bestValue._2._2,
                    evaluationMetric.bestValue._2._1)
                  out.collect((-2, CalculateMetricsSignal(leafId, true)))
                }
              }
            }
          }
        }
        println(s"---VFDT:$VFDT")
        println(s"---counterPerLeaf: $counterPerLeaf\n")
      }
      case _ =>
        throw new RuntimeException("- WTF is that, that you're " +
          "sending in the GlobalModelMapper" + value.getClass.toString)
    }
  }

  private def hoeffdingBound(n: Int): Double = {
    val R_square = math.pow(Utils.logBase2(resultingParameters.get(NumberOfClasses).get), 2.0)
    val delta = resultingParameters.get(VfdtDelta).get

    val hoeffdingBound = math.sqrt((R_square * math.log(1.0 / delta)) / (2.0 * n))
    println(s"---hoeffding bound: $hoeffdingBound")
    hoeffdingBound
  }

  private def nonSplittingEntropy(metrics012: (Int, Int, Int)): Double = {
    //P(Yes)Entropy(Yes) + P(No)Entropy(No)
    val total = (metrics012._1 + metrics012._2 + metrics012._3).toDouble
    var entropy = 0.0

    metrics012.productIterator.foreach(clazz => {
      val label = clazz.asInstanceOf[Int]
      if (label != 0) {
        entropy -= (clazz.asInstanceOf[Int] / total) * Utils.logBase2(clazz.asInstanceOf[Int] /
          total)
      }
    })
    //    val entropy = -(nOfYes / total) * Utils.logBase2(nOfYes / total) -
    //      (nOfNo / total) * Utils.logBase2(nOfNo / total)
    entropy
  }

}

/** This Flat Mapper can take as input a tuple2 (Int,Metrics), which is either broadcasted or
  * groupedByKey from the [[GlobalModelMapper]].
  *
  * inType1: (Int,VFDTAttributes) -> when an attribute is received. It updates the metrics for
  * the specific attribute.
  * inType2: (-2,CalculateMetricsSignal) -> When a signal for splitting a leaf is received. It
  * calculates which of the attributes is a better candidate for a split in that leaf.
  *
  * This mapper emits values of type [[EvaluationMetric]], which extends [[Metrics]].
  *
  */
class PartialVFDTMetricsMapper extends FlatMapFunction[(Int, Metrics), Metrics] {

  //[LeafId,HashMap[AttributeId,AttributeObserver]]
  val leafsObserver = new mutable.HashMap[Int, mutable.HashMap
    [Int, AttributeObserver[Metrics]]]()

  var attributesObserverTemp = mutable.HashMap[Int, AttributeObserver[Metrics]]()

  override def flatMap(value: (Int, Metrics), out: Collector[Metrics]): Unit = {


    value._2 match {
      case attribute: VFDTAttributes => {
        //take the class observer, else if there is no observer for that leaf
        leafsObserver.getOrElseUpdate(attribute.leaf, {
          new mutable.HashMap[Int, AttributeObserver[Metrics]]()
        })

        //check if there is an attributeSpectator for this attribute, update metrics
        //if there is no attributeSpectator create one, nominal or numerical
        attributesObserverTemp.getOrElseUpdate(value._1, {
          if (attribute.attributeType == AttributeType.Nominal) {
            new NominalAttributeObserver(attribute.nOfDifferentValues)
          }
          else {
            new NumericalAttributeObserver
          }
        }).updateMetricsWithAttribute(attribute)

        leafsObserver.put(attribute.leaf, attributesObserverTemp)
        //        println(leafsObserver)
      }

      case calcMetricsSignal: CalculateMetricsSignal => {

        leafsObserver.getOrElse(calcMetricsSignal.leaf, None) match {

          case leafToSplit: mutable.HashMap[Int, AttributeObserver[Metrics]] => {

            //[attributeId,(entropy,ListOfSplittingValues)]
            var bestAttributesToSplit = mutable.MutableList[(Int, (Double, List[Double]))]()

            if (!calcMetricsSignal.deleteObserver) {
              //            println(s"signal received when $counter attributes have been received")

              //[Int,HashMap[String,(#Yes,#No)]]
              for (attr <- leafToSplit) {
                val temp = attr._2.getSplitEvaluationMetric
                bestAttributesToSplit += ((attr._1, temp))
              }

              bestAttributesToSplit = bestAttributesToSplit sortWith ((x, y) => x._2._1 < y._2._1)
              //            System.err.println(bestAttributesToSplit)
              var bestAttr: (Int, (Double, List[Double])) = null
              var secondBestAttr: (Int, (Double, List[Double])) = null
              if (bestAttributesToSplit.size > 0) {
                bestAttr = bestAttributesToSplit(0)
              }
              if (bestAttributesToSplit.size > 1) {
                secondBestAttr = bestAttributesToSplit(1)
              }
              out.collect(EvaluationMetric(bestAttr, secondBestAttr, calcMetricsSignal.leaf))
            }
            else {
              leafsObserver.-(calcMetricsSignal.leaf) //delete observer of this leaf
            }
          }
          case None =>
          //            throw new RuntimeException(s"-There is no AttributeObserver for that
          // leaf:${calcMetricsSignal.leaf}-")
        }
      }
      case _ =>
        throw new RuntimeException("- WTF is that, that you're " +
          "sending in the  PartialVFDTMetricsMapper" + value.getClass.toString)
    }
  }
}
