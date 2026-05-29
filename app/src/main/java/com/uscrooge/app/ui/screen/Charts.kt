package com.uscrooge.app.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries

@Composable
fun EquityCurveChart(
    dataPoints: List<Pair<Float, Float>>,
    modifier: Modifier = Modifier,
    label: String = "Equity Curve"
) {
    if (dataPoints.size < 2) {
        Box(modifier = modifier) {
            Text("Not enough data", style = MaterialTheme.typography.bodySmall)
        }
        return
    }

    val modelProducer = remember { CartesianChartModelProducer() }

    LaunchedEffect(dataPoints) {
        modelProducer.runTransaction {
            lineSeries {
                series(
                    dataPoints.map { kotlin.math.round(it.first * 10000f) / 10000f },
                    dataPoints.map { it.second }
                )
            }
        }
    }

    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberLineCartesianLayer(),
                startAxis = VerticalAxis.rememberStart(),
                bottomAxis = HorizontalAxis.rememberBottom()
            ),
            modelProducer = modelProducer,
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
        )
    }
}

@Composable
fun MiniPriceChart(
    dataPoints: List<Float>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary
) {
    if (dataPoints.size < 2) return

    val modelProducer = remember { CartesianChartModelProducer() }

    LaunchedEffect(dataPoints) {
        modelProducer.runTransaction {
            lineSeries {
                series(dataPoints)
            }
        }
    }

    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(),
            startAxis = VerticalAxis.rememberStart(),
            bottomAxis = HorizontalAxis.rememberBottom()
        ),
        modelProducer = modelProducer,
        modifier = modifier.height(80.dp)
    )
}

@Composable
fun PortfolioAllocationPie(
    slices: List<PieSlice>,
    modifier: Modifier = Modifier
) {
    if (slices.isEmpty()) return

    val total = slices.sumOf { it.value }
    if (total <= 0.0) return

    Column(modifier = modifier) {
        Text(
            text = "Portfolio Allocation",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Canvas(
                modifier = Modifier.size(140.dp)
            ) {
                val diameter = size.minDimension
                val radius = diameter / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                val strokeWidth = 24f
                val arcSize = Size(diameter - strokeWidth, diameter - strokeWidth)
                val arcTopLeft = Offset(
                    center.x - arcSize.width / 2f,
                    center.y - arcSize.height / 2f
                )

                var startAngle = -90f
                slices.forEach { slice ->
                    val sweepAngle = (slice.value / total * 360).toFloat()
                    drawArc(
                        color = slice.color,
                        startAngle = startAngle,
                        sweepAngle = sweepAngle,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth)
                    )
                    startAngle += sweepAngle
                }

                drawContext.canvas.nativeCanvas.apply {
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textAlign = android.graphics.Paint.Align.CENTER
                        textSize = 36f
                        isFakeBoldText = true
                    }
                    drawText(
                        "€${String.format("%.0f", total)}",
                        center.x,
                        center.y + 12f,
                        paint
                    )
                }
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(start = 8.dp)
            ) {
                slices.forEach { slice ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Canvas(modifier = Modifier.size(10.dp)) {
                            drawCircle(slice.color)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${slice.label} ${String.format("%.1f", slice.value / total * 100)}%",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DrawdownChart(
    dataPoints: List<Float>,
    modifier: Modifier = Modifier
) {
    if (dataPoints.size < 2) return

    val drawdowns = remember(dataPoints) {
        var peak = dataPoints.first()
        dataPoints.map { current ->
            if (current > peak) peak = current
            if (peak > 0f) ((current - peak) / peak * 100) else 0f
        }
    }

    val modelProducer = remember { CartesianChartModelProducer() }

    LaunchedEffect(drawdowns) {
        modelProducer.runTransaction {
            lineSeries {
                series(drawdowns)
            }
        }
    }

    Column(modifier = modifier) {
        Text(
            text = "Drawdown",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberLineCartesianLayer(),
                startAxis = VerticalAxis.rememberStart(),
                bottomAxis = HorizontalAxis.rememberBottom()
            ),
            modelProducer = modelProducer,
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
        )
    }
}

data class PieSlice(
    val label: String,
    val value: Double,
    val color: Color
)

val presetColors = listOf(
    Color(0xFF4CAF50),
    Color(0xFF2196F3),
    Color(0xFFFF9800),
    Color(0xFFE57373),
    Color(0xFF9C27B0),
    Color(0xFF00BCD4),
    Color(0xFFFF5722),
    Color(0xFF607D8B)
)
