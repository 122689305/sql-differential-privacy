/*
 * Copyright (c) 2017 Uber Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.uber.engsec.dp.sql.relational_algebra

import java.io.{PrintWriter, StringWriter}

import com.uber.engsec.dp.schema.Schema
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.core.{Join, TableScan}
import org.apache.calcite.rel.externalize.RelJsonWriter
import org.apache.calcite.rel.rel2sql.RelToSqlConverter
import org.apache.calcite.rex.{RexCall, RexInputRef, RexNode}
import org.apache.calcite.sql.SqlDialect.DatabaseProduct
import org.apache.calcite.sql.SqlKind

object RelUtils {
  /** Extracts the left and right column indexes, respectively, used in an equijoin condition, or None if
    * the join node uses any other type of join condition (including an empty join condition).
    */
  def extractEquiJoinColumns(node: Join, condition: RexNode): Option[(Int,Int)] = {
    condition match {
      case c: RexCall if c.op.kind == SqlKind.EQUALS && c.operands.size == 2 =>

        val numColsLeft = node.getLeft.getRowType.getFieldCount

        (c.getOperands.get(0), c.getOperands.get(1)) match {
          case (first: RexInputRef, second: RexInputRef) =>
            val firstIdx = first.getIndex
            val secondIdx = second.getIndex

            if ((firstIdx < numColsLeft) && (secondIdx >= numColsLeft))
              Some((firstIdx, secondIdx-numColsLeft))
            else if ((secondIdx < numColsLeft) && (firstIdx >= numColsLeft))
              Some((secondIdx, firstIdx-numColsLeft))
            else
              None

          case _ => None
        }

      case _ => None
    }
  }

  /** Decomposes a given expression into a list of conjunctive clauses. */
  def decomposeConjunction(expression: RexNode): List[RexNode] = {
    import scala.collection.JavaConverters._

    if (expression == null || expression.isAlwaysTrue)
      Nil

    expression match {
      case c: RexCall if c.isA(SqlKind.AND) => c.getOperands.asScala.flatMap{ decomposeConjunction }.toList
      case _ => List(expression)
    }
  }

  /** Returns a JSON representation of the given relational algebra tree.
    */
  def relToJson(rel: RelNode): String = {
    val pw = new PrintWriter(new StringWriter)
    val writer = new RelJsonWriter

    rel.explain(writer)
    writer.asString
  }

  /** Converts the given relational algebra tree to a SQL string.
    */
  def relToSql(rel: RelNode): String = {
    val dialect = DatabaseProduct.valueOf(Schema.currentDb.dialect.toUpperCase).getDialect
    val converter = new RelToSqlConverter(dialect)
    converter.visitChild(0, rel).asStatement.toSqlString(dialect).getSql
  }

  /******************************************************************************************************************
    * Helper methods, may be called by analysis transfer functions.
    ****************************************************************************************************************/

  /** Retrieves the config properties for the database table represented by the given node. Returns empty map if no
    * config is defined for the table.
    */
  def getTableProperties(node: TableScan): Map[String, String] = {
    import scala.collection.JavaConverters._
    val tableName = node.getTable.getQualifiedName.asScala.mkString(".")
    Schema.getTableProperties(tableName)
  }

  /** Retrieves the config properties for the database table represented by the given node. Returns empty map if no
    * config is defined for the table/column.
    */
  def getColumnProperties(node: TableScan, colIdx: Int): Map[String, String] = {
    import scala.collection.JavaConverters._
    val tableName = node.getTable.getQualifiedName.asScala.mkString(".")
    val colName = node.getRowType.getFieldNames.get(colIdx)
    Schema.getSchemaMapForTable(tableName).get(colName).map { _.properties }.getOrElse{ Map.empty }
  }

  /** Returns the fully qualified table and column name. */
  def getQualifiedColumnName(table: TableScan, colIdx: Int): String = {
    import scala.collection.JavaConverters._
    val tableName = table.getTable.getQualifiedName.asScala.mkString(".")
    val colName = table.getRowType.getFieldNames.get(colIdx)
    s"$tableName.$colName"
  }
}
