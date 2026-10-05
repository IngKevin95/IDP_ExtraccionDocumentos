package com.idp.extraction.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Casos ficticios de los validadores (especificacion validadores AC-01 a AC-09). */
class ValidatorsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 5);
    private static final ValidationContext CTX = ValidationContext.of(Map.of(), TODAY);

    private static ValidationContext doc(String k, String v) {
        return ValidationContext.of(Map.of(k, v), TODAY);
    }

    private static ValidationContext row(String tipo) {
        return new ValidationContext(Map.of(), Map.of("tipo_identificacion", tipo), TODAY);
    }

    // ---- AC-01 radicado de 23 digitos --------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"11001310300520240012300", "11001-31-03-005-2024-00123-00", "11001 31 03 005 2024 00123 00",
        "11001.31.03.005.2024.00123.00", "05001400300120260000100"})
    void ac01_radicadoValidoPasa(String value) {
        assertThat(new RadicadoValidator().validate(value, CTX).passed()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
        "1100131030052024001230,RADICADO_LONGITUD",
        "110013103005202400123000,RADICADO_LONGITUD",
        "1100131030052024001230A,RADICADO_CARACTER_INVALIDO",
        "11001310300518000012300,RADICADO_ANIO",
        "11001310300520990012300,RADICADO_ANIO",
        "'',RADICADO_VACIO",
        "'   ',RADICADO_VACIO"})
    void ac01_radicadoInvalidoFalla(String value, String code) {
        ValidationResult r = new RadicadoValidator().validate(value, CTX);
        assertThat(r.failed()).isTrue();
        assertThat(r.code()).isEqualTo(code);
    }

    @Test
    void ac01_radicadoNuloFalla() {
        assertThat(new RadicadoValidator().validate(null, CTX).code()).isEqualTo("RADICADO_VACIO");
    }

    // ---- AC-08 proteccion ReDoS ---------------------------------------------------------------------

    @Test
    void ac08_cadenaAnomalaDe50000CaracteresSeRechazaAntesDeRegex() {
        String huge = "1".repeat(50_000);
        long start = System.nanoTime();
        ValidationResult radicado = new RadicadoValidator().validate(huge, CTX);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(radicado.code()).isEqualTo("RADICADO_LONGITUD_EXCEDIDA");
        assertThat(elapsedMs).isLessThan(50);
        assertThat(new NitValidator().validate(huge, CTX).code()).isEqualTo("NIT_LONGITUD_EXCEDIDA");
        assertThat(new CedulaValidator().validate(huge, CTX).code()).isEqualTo("CEDULA_LONGITUD_EXCEDIDA");
        assertThat(new DateCoherenceValidator().validate(huge, CTX).code()).isEqualTo("FECHA_LONGITUD_EXCEDIDA");
    }

    // ---- AC-02 NIT modulo 11 ------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"900123456-8", "900.123.456-8", "9001234568", "860002964-4", "901234567-7", "100000-1"})
    void ac02_nitConDigitoCorrectoPasa(String value) {
        assertThat(new NitValidator().validate(value, CTX).passed()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
        "900123456-9,NIT_DV_INCORRECTO",
        "900123456-0,NIT_DV_INCORRECTO",
        "900123456-,NIT_DV_FORMATO",
        "900123456-88,NIT_DV_FORMATO",
        "900123456-X,NIT_DV_FORMATO",
        "9001234,NIT_FORMATO",
        "ABC-1,NIT_FORMATO",
        "12-3,NIT_FORMATO",
        "'',NIT_VACIO"})
    void ac02_nitInvalidoFalla(String value, String code) {
        ValidationResult r = new NitValidator().validate(value, CTX);
        assertThat(r.failed()).isTrue();
        assertThat(r.code()).isEqualTo(code);
    }

    @Test
    void ac02_nitBarridoExhaustivoCoincideConAlgoritmoDeReferencia() {
        int[] w = {3, 7, 13, 17, 19, 23, 29, 37, 41, 43, 47, 53, 59, 67, 71};
        NitValidator v = new NitValidator();
        for (int base = 100_000; base < 100_300; base++) {
            String s = Integer.toString(base);
            int sum = 0;
            for (int i = 0; i < s.length(); i++) {
                sum += (s.charAt(s.length() - 1 - i) - '0') * w[i];
            }
            int r = sum % 11;
            int expected = r > 1 ? 11 - r : r;
            for (int dv = 0; dv <= 9; dv++) {
                boolean ok = v.validate(s + "-" + dv, CTX).passed();
                assertThat(ok).as("NIT %s-%d", s, dv).isEqualTo(dv == expected);
            }
        }
    }

    // ---- AC-03 cedula -------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"1234567", "123456", "1234567890", "1.234.567", "80123456"})
    void ac03_cedulaValidaPasa(String value) {
        assertThat(new CedulaValidator().validate(value, CTX).passed()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
        "12345,CEDULA_LONGITUD",
        "12345678901,CEDULA_LONGITUD",
        "12345-6,CEDULA_CON_DV",
        "12A4567,CEDULA_CARACTER_INVALIDO",
        "'',CEDULA_VACIA"})
    void ac03_cedulaInvalidaFalla(String value, String code) {
        ValidationResult r = new CedulaValidator().validate(value, CTX);
        assertThat(r.failed()).isTrue();
        assertThat(r.code()).isEqualTo(code);
    }

    // ---- tipo de documento y validador condicional ---------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"CC", "cc", "C.C.", "C.E.", "CE", "NIT", "nit", " N I T "})
    void tipoDocumentoSoportado(String value) {
        assertThat(new TipoIdentificacionValidator().validate(value, CTX).passed()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TI", "PA", "", "PASAPORTE-LARGO-XXXXXXXX"})
    void tipoDocumentoNoSoportado(String value) {
        assertThat(new TipoIdentificacionValidator().validate(value, CTX).code()).isEqualTo("TIPO_DOC_NO_SOPORTADO");
    }

    @Test
    void condicionalRutea() {
        var v = new IdentificacionCondicionalValidator(new CedulaValidator(), new NitValidator());
        assertThat(v.validate("900123456-8", row("NIT")).passed()).isTrue();
        assertThat(v.validate("900123456-9", row("NIT")).code()).isEqualTo("NIT_DV_INCORRECTO");
        assertThat(v.validate("1234567", row("CC")).passed()).isTrue();
        assertThat(v.validate("1234567", row("CE")).passed()).isTrue();
        assertThat(v.validate("900123456-8", row("CC")).code()).isEqualTo("CEDULA_CON_DV");
        assertThat(v.validate("1234567", row("NIT")).code()).isEqualTo("NIT_FORMATO");
        assertThat(v.validate("1234567", row("TI")).code()).isEqualTo("TIPO_DOC_INDETERMINADO");
        assertThat(v.validate("1234567", CTX).code()).isEqualTo("TIPO_DOC_INDETERMINADO");
    }

    // ---- AC-04 monto letras vs numeros (RN-02) --------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
        "15000000,quince millones de pesos",
        "'15.000.000,00',QUINCE MILLONES DE PESOS M/CTE",
        "'$ 1.250.000',un millón doscientos cincuenta mil pesos",
        "100000,cien mil pesos",
        "21,veintiún pesos",
        "2000000000,dos mil millones de pesos",
        "1000000,un millón de pesos",
        "1000,mil pesos",
        "101000,ciento un mil pesos",
        "350,trescientos cincuenta pesos",
        "135,ciento treinta y cinco pesos",
        "1000000000000,un billón de pesos moneda corriente"})
    void ac04_montoCoincidePasa(String numeros, String letras) {
        ValidationResult r = new MontoValidator().validate(numeros, doc("monto_letras", letras));
        assertThat(r.passed()).as(letras).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
        "10000,cien mil pesos,MONTO_DISCREPANCIA",
        "100000.50,cien mil pesos,MONTO_DISCREPANCIA",
        "1500000,un millón doscientos mil pesos,MONTO_DISCREPANCIA",
        "100000,cien mil pesos con cincuenta centavos,MONTO_LETRAS_ILEGIBLE",
        "100000,abc,MONTO_LETRAS_ILEGIBLE",
        "12abc,cien mil pesos,MONTO_NUMEROS_ILEGIBLE"})
    void ac04_montoDiscrepanteFalla(String numeros, String letras, String code) {
        assertThat(new MontoValidator().validate(numeros, doc("monto_letras", letras)).code()).isEqualTo(code);
    }

    @Test
    void ac04_montoSinLetrasOSinNumerosFalla() {
        assertThat(new MontoValidator().validate("1000", CTX).code()).isEqualTo("MONTO_LETRAS_AUSENTE");
        assertThat(new MontoValidator().validate("", doc("monto_letras", "mil")).code()).isEqualTo("MONTO_NUMEROS_VACIO");
    }

    @Test
    void numerosEnLetrasCasosLimite() {
        assertThat(SpanishNumberWords.parse("veintidós")).contains(22L);
        assertThat(SpanishNumberWords.parse("treinta y uno")).contains(31L);
        assertThat(SpanishNumberWords.parse("mil novecientos noventa y nueve")).contains(1999L);
        assertThat(SpanishNumberWords.parse("dos millones quinientos mil")).contains(2_500_000L);
        assertThat(SpanishNumberWords.parse("cero")).contains(0L);
        assertThat(SpanishNumberWords.parse("hola mundo")).isEmpty();
        assertThat(SpanishNumberWords.parse("")).isEmpty();
        assertThat(SpanishNumberWords.parse(null)).isEmpty();
        assertThat(SpanishNumberWords.parse("mil ".repeat(200))).isEmpty();
    }

    @Test
    void montosNumericosSeInterpretanSinPerderCifras() {
        assertThat(AmountParser.parse("10.000")).contains(new BigDecimal("10000"));
        assertThat(AmountParser.parse("10,000")).contains(new BigDecimal("10000"));
        assertThat(AmountParser.parse("10.000,50")).contains(new BigDecimal("10000.50"));
        assertThat(AmountParser.parse("10,000.50")).contains(new BigDecimal("10000.50"));
        assertThat(AmountParser.parse("1.5")).contains(new BigDecimal("1.5"));
        assertThat(AmountParser.parse("1,5")).contains(new BigDecimal("1.5"));
        assertThat(AmountParser.parse("1.234.567")).contains(new BigDecimal("1234567"));
        assertThat(AmountParser.parse("1,234,567")).contains(new BigDecimal("1234567"));
        assertThat(AmountParser.parse("$ 15000000")).contains(new BigDecimal("15000000"));
        assertThat(AmountParser.parse("0")).contains(BigDecimal.ZERO);
        for (String bad : List.of("12.34.56", "abc", "", ".5", "1.000.00", "1".repeat(41))) {
            assertThat(AmountParser.parse(bad)).as(bad).isEmpty();
        }
    }

    // ---- AC-05 fechas coherentes ----------------------------------------------------------------------

    @Test
    void ac05_fechaNoPuedeSerPosteriorALaRecepcion() {
        var v = new DateCoherenceValidator();
        assertThat(v.validate("2026-09-30", CTX).passed()).isTrue();
        assertThat(v.validate("2026-10-05", CTX).passed()).isTrue();
        assertThat(v.validate("2026-10-06", CTX).code()).isEqualTo("FECHA_FUTURA");
        assertThat(v.validate("15/03/2026", CTX).code()).isEqualTo("FECHA_FORMATO");
        assertThat(v.validate("1980-01-01", CTX).code()).isEqualTo("FECHA_ANTIGUA");
        assertThat(v.validate(" ", CTX).code()).isEqualTo("FECHA_VACIA");
        assertThat(v.validate("2026-02-30", CTX).code()).isEqualTo("FECHA_FORMATO");
    }

    // ---- AC-06 suma de tablas (RN-05) -----------------------------------------------------------------

    @Test
    void ac06_sumaDeTablaCoincideConTotal() {
        var v = new TableSumValidator();
        List<Map<String, String>> rows = List.of(Map.of("monto", "10.000.000"), Map.of("monto", "5.000.000"));
        assertThat(v.validate(rows, "monto", "15000000").passed()).isTrue();
        assertThat(v.validate(rows, "monto", "14000000").code()).isEqualTo("SUMA_DISCREPANCIA");
        assertThat(v.validate(rows, "monto", "abc").code()).isEqualTo("SUMA_TOTAL_ILEGIBLE");
        assertThat(v.validate(List.of(Map.of("monto", "x")), "monto", "1").code()).isEqualTo("SUMA_CELDA_ILEGIBLE");
        assertThat(v.validate(List.of(), "monto", "1").status()).isEqualTo(ValidationResult.Status.NOT_APPLICABLE);
        assertThat(v.validate(rows, "monto", " ").status()).isEqualTo(ValidationResult.Status.NOT_APPLICABLE);
        assertThat(v.validate(List.of(Map.of("otra", "1")), "monto", "1").status())
            .isEqualTo(ValidationResult.Status.NOT_APPLICABLE);
    }

    // ---- AC-07 juzgado en catalogo ---------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"Juzgado 05 Civil Municipal", "Juzgado Quinto Civil Municipal de Bogotá",
        "JUZGADO 12 CIVIL DEL CIRCUITO DE MEDELLIN", "Juzgado 3 Civil Munipal", "Juzgado Promiscuo Municipal de Sopó",
        "Juzgado 2 de Pequeñas Causas y Competencia Múltiple"})
    void ac07_juzgadoEnCatalogoPasa(String value) {
        assertThat(JuzgadoValidator.withDefaultCatalog().validate(value, CTX).passed()).as(value).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Juzgado Inventado de Marte", "Tienda de Barrio", "Juzgado Civil", ""})
    void ac07_juzgadoFueraDeCatalogoFalla(String value) {
        assertThat(JuzgadoValidator.withDefaultCatalog().validate(value, CTX).failed()).as(value).isTrue();
    }

    @Test
    void ac07_juzgadoDemasiadoLargoSeRechaza() {
        assertThat(JuzgadoValidator.withDefaultCatalog().validate("x".repeat(201), CTX).code())
            .isEqualTo("JUZGADO_LONGITUD_EXCEDIDA");
    }

    // ---- tipologia valida y registro ----------------------------------------------------------------------

    @Test
    void tipologiaValidaSoloCuatroCodigos() {
        var v = new TipologiaValidaValidator();
        for (String ok : List.of("EC", "EJ", "DC", "DJ")) {
            assertThat(v.validate(ok, CTX).passed()).isTrue();
        }
        assertThat(v.validate("XX", CTX).failed()).isTrue();
        assertThat(v.validate(null, CTX).failed()).isTrue();
    }

    @Test
    void registroContieneElCatalogoCompleto() {
        ValidatorRegistry registry = ValidatorRegistry.defaults();
        for (String id : List.of("radicado_23_digitos", "juzgado_catalogo", "monto_numeros_letras", "fechas_coherentes",
            "tipo_doc_valido", "cedula_formato", "nit_modulo_11", "cedula_nit_condicional", "tipologia_valida")) {
            assertThat(registry.contains(id)).as(id).isTrue();
        }
        assertThat(registry.contains("validador_ficticio")).isFalse();
    }
}
