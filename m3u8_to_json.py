#!/usr/bin/env python3
"""
محول ملفات M3U8 إلى صيغة JSON لتطبيق يلا گول
استخدم: python3 m3u8_to_json.py input.m3u8 output.json "اسم القناة" "التصنيف"
"""

import sys
import json
import re
from urllib.parse import urlparse, urljoin

def parse_m3u8(content, base_url=""):
    """تحليل ملف M3U8 واستخراج الروابط"""
    channels = []
    current_name = ""
    
    for line in content.split('\n'):
        line = line.strip()
        
        # تجاهل التعليقات الفارغة و EXTINF
        if not line or line.startswith('#EXT-X-') or line.startswith('#EXTM3U'):
            continue
            
        # استخراج اسم القناة من EXTINF
        if line.startswith('#EXTINF:'):
            match = re.search(r',\s*(.+)$', line)
            if match:
                current_name = match.group(1).strip()
            continue
        
        # إذا كان رابطاً
        if line.startswith('http://') or line.startswith('https://') or line.endswith('.ts') or line.endswith('.m3u8'):
            full_url = line if line.startswith('http') else urljoin(base_url, line)
            
            channel_name = current_name if current_name else f"قناة {len(channels) + 1}"
            
            channels.append({
                "name": channel_name,
                "url": full_url
            })
            current_name = ""
    
    return channels

def m3u8_to_yalla_json(m3u8_content, channel_name, category, base_url=""):
    """تحويل M3U8 إلى صيغة تطبيق يلا گول"""
    streams = parse_m3u8(m3u8_content, base_url)
    
    if not streams:
        return None
    
    # تجميع الروابط كسيرفرات متعددة لنفس القناة
    servers = []
    for i, stream in enumerate(streams):
        quality = "HD" if "HD" in stream["name"].upper() or "720" in stream["name"] or "1080" in stream["name"] else "SD"
        if "SD" in stream["name"].upper() or "480" in stream["name"]:
            quality = "SD"
        
        servers.append({
            "n": f"سيرفر {i+1} - {stream['name']} ({quality})",
            "u": stream["url"],
            "t": "v"
        })
    
    return {
        "n": channel_name,
        "c": category,
        "s": servers
    }

def main():
    if len(sys.argv) < 4:
        print("الاستخدام: python3 m3u8_to_json.py <ملف_المدخل.m3u8> <ملف_المخرج.json> <اسم_القناة> <التصنيف>")
        print("مثال: python3 m3u8_to_json.py alwan1.m3u8 output.json \"الوان سبورت 1\" \"رياضة إضافي\"")
        sys.exit(1)
    
    input_file = sys.argv[1]
    output_file = sys.argv[2]
    channel_name = sys.argv[3]
    category = sys.argv[4] if len(sys.argv) > 4 else "عام"
    
    try:
        with open(input_file, 'r', encoding='utf-8') as f:
            m3u8_content = f.read()
        
        result = m3u8_to_yalla_json(m3u8_content, channel_name, category)
        
        if not result:
            print("❌ لم يتم العثور على أي روابط في الملف")
            sys.exit(1)
        
        # حفظ النتيجة
        with open(output_file, 'w', encoding='utf-8') as f:
            json.dump([result], f, ensure_ascii=False, indent=2)
        
        print(f"✅ تم التحويل بنجاح!")
        print(f"📺 القناة: {channel_name}")
        print(f"📂 التصنيف: {category}")
        print(f"🔗 عدد السيرفرات: {len(result['s'])}")
        print(f"💾 الملف المحفوظ: {output_file}")
        print("\n📋 المحتوى:")
        print(json.dumps([result], ensure_ascii=False, indent=2))
        
    except FileNotFoundError:
        print(f"❌ الملف '{input_file}' غير موجود")
        sys.exit(1)
    except Exception as e:
        print(f"❌ خطأ: {e}")
        sys.exit(1)

if __name__ == "__main__":
    main()
