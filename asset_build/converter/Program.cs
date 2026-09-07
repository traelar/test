using System.Xml;
using CodeWalker.GameFiles;

if (args.Length != 2)
{
    Console.Error.WriteLine("Usage: Converter <xml-directory> <output-directory>");
    return 2;
}

var inputDirectory = Path.GetFullPath(args[0]);
var outputDirectory = Path.GetFullPath(args[1]);
Directory.CreateDirectory(outputDirectory);

var converted = 0;
foreach (var inputPath in Directory.GetFiles(inputDirectory, "*.xml").OrderBy(p => p))
{
    var lower = inputPath.ToLowerInvariant();
    var format = XmlMeta.GetXMLFormat(lower, out var trimLength);
    if (format == MetaFormat.XML)
    {
        Console.Error.WriteLine($"Unsupported XML format: {inputPath}");
        return 3;
    }

    var document = new XmlDocument();
    document.Load(inputPath);

    var filenameWithoutXml = Path.GetFileNameWithoutExtension(inputPath);
    var modelName = filenameWithoutXml[..^trimLength];
    var textureFolder = Path.Combine(inputDirectory, modelName);
    if (!Directory.Exists(textureFolder)) textureFolder = null;

    var bytes = XmlMeta.GetData(document, format, textureFolder!);
    if (bytes is null || bytes.Length < 256)
    {
        Console.Error.WriteLine($"CodeWalker failed to compile {inputPath}");
        return 4;
    }

    var outputName = Path.GetFileName(inputPath)[..^4];
    var outputPath = Path.Combine(outputDirectory, outputName);
    File.WriteAllBytes(outputPath, bytes);
    Console.WriteLine($"Compiled {outputName}: {bytes.Length:N0} bytes");
    converted++;
}

if (converted < 2)
{
    Console.Error.WriteLine($"Expected YDR and YTYP, compiled only {converted} file(s).");
    return 5;
}

return 0;
