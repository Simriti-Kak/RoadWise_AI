import pypandoc
import os

def main():
    try:
        print("Ensuring pandoc is installed...")
        pypandoc.get_pandoc_version()
    except OSError:
        print("Pandoc not found, downloading...")
        pypandoc.download_pandoc()
    
    print("Pandoc ready.")
    
    files = [
        "00_Front_Matter.md",
        "01_Chapter_1.md",
        "02_Chapter_2.md",
        "03_Chapter_3.md",
        "04_Chapter_4.md",
        "05_Chapter_5.md",
        "06_Chapter_6.md",
        "07_References.md"
    ]
    
    report_dir = r"c:\Roadwise\report"
    combined_md = ""
    for f in files:
        f_path = os.path.join(report_dir, f)
        if os.path.exists(f_path):
            with open(f_path, "r", encoding="utf-8") as fp:
                combined_md += fp.read() + "\n\n"
        else:
            print(f"Warning: {f_path} not found.")

    combined_path = r"c:\Roadwise\Roadwise_Project_Report.md"
    with open(combined_path, "w", encoding="utf-8") as fp:
        fp.write(combined_md)
        
    print(f"Combined markdown saved to {combined_path}")
    
    output_docx = r"c:\Roadwise\Roadwise_Project_Report.docx"
    print(f"Converting to {output_docx}...")
    pypandoc.convert_file(combined_path, "docx", outputfile=output_docx)
    print("Done!")

if __name__ == "__main__":
    main()
