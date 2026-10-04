# Plan de Implementación: Motor de Fiabilidad

## Módulo Maven
- `libs/domain-reliability`

## Paquetes
- `com.banco.idp.domain.reliability`
- `com.banco.idp.domain.reliability.calibration`
- `com.banco.idp.domain.reliability.metrics`

## Clases principales
- `ReliabilityEngine`: Core que combina señales (Logprobs, DeterministicResult, GroundingScore).
- `Calibrator`: Interfaz para escalado Isotónico/Platt.
- `IsotonicCalibratorImpl`: Algoritmo en memoria calibrado previamente.
- `ConfidenceDecision`: DTO con la decisión (AUTO, HITL, REJECT) y triggers de cascada.

## Migraciones Flyway
- `V1_0_2__create_extraction_confidence_log.sql`: (Esquema por Tenant).

## Estrategia de tests
- Tests unitarios de algoritmos de calibración y combinación de señales.
- Test de pipeline (Gate CI) donde un mini golden set corre por el motor verificando que el ECE esperado se cumpla.
