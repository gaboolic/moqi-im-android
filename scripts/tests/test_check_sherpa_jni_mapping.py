"""Run with: python -m unittest discover -s scripts/tests -v"""
import importlib.util
import io
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

SCRIPT = Path(__file__).resolve().parents[1] / "check_sherpa_jni_mapping.py"
spec = importlib.util.spec_from_file_location("checker", SCRIPT)
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)
FIXTURES = Path(__file__).parent / "fixtures"
CONTRACT = [
    (checker.PREFIX + "OnlineRecognizerConfig", {"decodingMethod", "modelConfig"}, set()),
    (checker.PREFIX + "OnlineRecognizer", {"ptr"}, {"newFromAsset"}),
]


def minimal_class():
    """An actual class-file fixture with a field and native method, no javac needed."""
    u2 = lambda value: struct.pack(">H", value)
    pool = []

    def utf8(text):
        value = text.encode()
        pool.append(b"\x01" + u2(len(value)) + value)
        return len(pool)

    def clazz(text):
        name = utf8(text)
        pool.append(b"\x07" + u2(name))
        return len(pool)

    this = clazz("com/k2fsa/sherpa/onnx/OnlineRecognizerConfig")
    superclass = clazz("java/lang/Object")
    field, field_type = utf8("decodingMethod"), utf8("Ljava/lang/String;")
    method, method_type = utf8("newFromAsset"), utf8("()J")
    ordinary, ordinary_type = utf8("getDecodingMethod"), utf8("()Ljava/lang/String;")
    return (b"\xca\xfe\xba\xbe" + u2(0) + u2(52) + u2(len(pool) + 1)
            + b"".join(pool) + u2(1) + u2(this) + u2(superclass) + u2(0)
            + u2(1) + u2(2) + u2(field) + u2(field_type) + u2(0)
            + u2(2) + u2(0x0101) + u2(method) + u2(method_type) + u2(0)
            + u2(0x0401) + u2(ordinary) + u2(ordinary_type) + u2(0) + u2(0))


class MappingTests(unittest.TestCase):
    def fixture(self, name="sherpa-kept.txt"):
        return (FIXTURES / name).read_text(encoding="utf-8")

    def check(self, text):
        return checker.check_contract(CONTRACT, checker.parse_mapping(text))

    def test_kept_classes_fields_and_native_methods_pass(self):
        self.assertEqual([], self.check(self.fixture()))

    def test_real_regression_decoding_method_renamed(self):
        errors = self.check(self.fixture("sherpa-renamed.txt"))
        self.assertTrue(any("OnlineRecognizerConfig.decodingMethod -> g" in e for e in errors))
        self.assertEqual(3, len(errors))

    def test_omitted_identity_mapping_uses_dex_definitions(self):
        text = self.fixture().replace("    java.lang.String decodingMethod -> decodingMethod\n", "")
        definitions = {name: (name, fields, natives) for name, fields, natives in CONTRACT}
        self.assertEqual([], checker.check_contract(CONTRACT, checker.parse_mapping(text), definitions))
        definitions[CONTRACT[0][0]] = (CONTRACT[0][0], {"modelConfig"}, set())
        errors = checker.check_contract(CONTRACT, checker.parse_mapping(text), definitions)
        self.assertTrue(any("decodingMethod -> <missing>" in e for e in errors))

    def test_renamed_class_fails_even_when_members_kept(self):
        text = self.fixture().replace(" -> " + checker.PREFIX + "OnlineRecognizerConfig:", " -> a.b:")
        self.assertTrue(any("Renamed class:" in e for e in self.check(text)))

    def test_missing_class_or_empty_mapping_fails(self):
        self.assertEqual(2, len(self.check("# no classes\n")))

    def test_renamed_native_method_fails(self):
        text = self.fixture().replace(" -> newFromAsset", " -> x")
        self.assertTrue(any("Native method:" in e for e in self.check(text)))

    def test_method_line_ranges_do_not_look_like_fields(self):
        mapping = checker.parse_mapping(self.fixture())
        entry = mapping[checker.PREFIX + "OnlineRecognizer"]
        self.assertEqual({"newFromAsset"}, entry[2]["newFromAsset"])
        self.assertEqual({"ptr": "ptr"}, entry[1])

    def test_read_class_contract_from_binary(self):
        self.assertEqual((checker.PREFIX + "OnlineRecognizerConfig", {"decodingMethod"}, {"newFromAsset"}),
                         checker.class_contract(minimal_class()))

    def test_dex_binary_defined_fields_and_native_flags(self):
        # Minimal DEX table fixture with one class, field, native and non-native method.
        data = bytearray(256)
        data[:8] = b"dex\n035\0"
        def put(offset, value):
            struct.pack_into("<I", data, offset, value)
        strings = ["Lcom/k2fsa/sherpa/onnx/OnlineRecognizerConfig;", "decodingMethod", "newFromAsset", "ordinary"]
        put(56, 4)
        put(60, 112)
        put(64, 1)
        put(68, 128)
        put(80, 1)
        put(84, 132)
        put(88, 2)
        put(92, 140)
        put(96, 1)
        put(100, 156)
        put(136, 1)  # field name index
        put(144, 2)  # native method name index
        put(152, 3)  # ordinary method name index
        put(180, 188)  # class_data_off
        data[188:201] = bytes([0, 1, 2, 0, 0, 2, 0, 0x81, 2, 0, 1, 1, 0])
        for i, text in enumerate(strings):
            put(112 + i * 4, len(data))
            encoded = text.encode()
            data.extend(bytes([len(encoded)]) + encoded + b"\0")
        definitions = checker.dex_contract(bytes(data))
        self.assertEqual((CONTRACT[0][0], {"decodingMethod"}, {"newFromAsset"}), definitions[CONTRACT[0][0]])

    def test_missing_dex_class_or_native_method_fails(self):
        errors = checker.check_contract(CONTRACT, checker.parse_mapping(self.fixture()), {})
        self.assertTrue(any("Missing DEX class:" in e for e in errors))
        self.assertTrue(any("Native method:" in e for e in errors))

    def test_actual_aar_container_and_no_vacuous_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            aar_path = Path(directory) / "fixture.aar"
            for has_sherpa in (True, False):
                jar_bytes = io.BytesIO()
                with zipfile.ZipFile(jar_bytes, "w") as jar:
                    if has_sherpa:
                        jar.writestr("com/k2fsa/sherpa/onnx/OnlineRecognizerConfig.class", minimal_class())
                with zipfile.ZipFile(aar_path, "w") as aar:
                    aar.writestr("classes.jar", jar_bytes.getvalue())
                if has_sherpa:
                    self.assertEqual(1, len(checker.aar_contract(aar_path)))
                else:
                    with self.assertRaisesRegex(ValueError, "no Sherpa classes"):
                        checker.aar_contract(aar_path)


if __name__ == "__main__":
    unittest.main()
