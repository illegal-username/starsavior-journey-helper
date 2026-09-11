"""Catch silent fallback and runtime formatting failures in localized Android UI."""
import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2] / 'app' / 'src' / 'main' / 'res'
FOLDERS = ['values', 'values-ko', 'values-ja', 'values-b+zh+Hant', 'values-b+zh+Hans', 'values-fr', 'values-de', 'values-es', 'values-pt', 'values-in', 'values-vi']

def strings(folder):
    return {item.attrib['name']: item.text or ''
            for item in ET.parse(ROOT/folder/'strings.xml').getroot()
            if item.tag == 'string' and item.attrib.get('translatable') != 'false'}

def placeholders(value):
    return sorted(re.findall(r'%(\d+)\$[,\d.]*([sdf])', value.replace('%%', '')))

class LocalizationResourcesTest(unittest.TestCase):
    def test_every_language_has_all_messages_with_matching_format_arguments(self):
        default = strings('values')
        for folder in FOLDERS:
            translated = strings(folder)
            self.assertEqual(set(default), set(translated), folder)
            for name, value in translated.items():
                with self.subTest(folder=folder, name=name):
                    self.assertTrue(value.strip().strip('"'))
                    self.assertEqual(placeholders(default[name]), placeholders(value))
                    self.assertNotRegex(value.replace('%%', ''), r'%(?!\d+\$)')

    def test_same_progress_notice_stays_neutral(self):
        korean = strings('values-ko')
        self.assertEqual('선택에 따른 차이 없음', korean['same_progress_title'].strip('"'))
        self.assertEqual('어느 쪽을 골라도 동일하게 진행됩니다.', korean['same_progress_message'].strip('"'))

if __name__ == '__main__':
    unittest.main()
