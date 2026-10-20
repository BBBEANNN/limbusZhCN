import com.example.limbuszhcn.container.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import java.io.*;
import java.security.MessageDigest;

/** Offline audit using the compiled production collector/compiler. Inputs stay local. */
class AuditTranslations {
    static void text(DataOutputStream out,String value) throws Exception {
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);
    }
    static Map<String,String> sources(ZipFile zip) throws Exception {
        Map<String,String> result=new TreeMap<>();
        for (var entries=zip.entries();entries.hasMoreElements();) {
            ZipEntry e=entries.nextElement();String name=e.getName();
            if(e.isDirectory()||!name.endsWith(".json"))continue;
            String path;
            if(name.startsWith("jp/"))path=name;
            else if(name.contains("/JP/")) {
                String relative=name.substring(name.indexOf("/JP/")+4);
                int slash=relative.lastIndexOf('/');
                path="jp/"+relative.substring(0,slash+1)+"JP_"+relative.substring(slash+1);
            } else continue;
            result.put(path,new String(zip.getInputStream(e).readAllBytes(),StandardCharsets.UTF_8));
        }
        return result;
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException(
            "Usage: java --class-path <app Kotlin classes;stdlib;org.json> scripts/AuditTranslations.java <source.zip> <translation.zip> <output-directory> <package-version>");
        try(ZipFile original=new ZipFile(args[0]);ZipFile patch=new ZipFile(args[1])) {
            var originals=sources(original);var translations=new TreeMap<String,String>();
            String prefix="LimbusCompany_Data/Lang/LLC_zh-CN/";
            for(var entries=patch.entries();entries.hasMoreElements();) {
                ZipEntry e=entries.nextElement();String name=e.getName();
                if(e.isDirectory()||!name.startsWith(prefix)||!name.endsWith(".json"))continue;
                String relative=name.substring(prefix.length());int slash=relative.lastIndexOf('/');
                String path="jp/"+relative.substring(0,slash+1)+"JP_"+relative.substring(slash+1);
                translations.put(path,new String(patch.getInputStream(e).readAllBytes(),StandardCharsets.UTF_8));
            }
            var compiler=new TranslationIndexCompiler();var records=new ArrayList<TranslationIndexRecord>();
            var normal=new TranslationRecordCollector(false);var auditCollector=new TranslationRecordCollector(true);
            int available=0,invalid=0;
            var invalidPaths=new ArrayList<String>();
            for(var entry:translations.entrySet()) {
                String source=originals.get(entry.getKey());if(source==null)continue;
                available++;
                try {
                    normal.collect(entry.getKey(),source,entry.getValue()).forEach(compiler::add);
                    records.addAll(auditCollector.collect(entry.getKey(),source,entry.getValue()));
                } catch(Exception e) {invalid++;invalidPaths.add(entry.getKey());}
            }
            var archiveDigest=MessageDigest.getInstance("SHA-256");
            try(var input=Files.newInputStream(Path.of(args[1]))) {
                byte[] buffer=new byte[128*1024];int read;
                while((read=input.read(buffer))!=-1)archiveDigest.update(buffer,0,read);
            }
            var index=compiler.compile(args[3],HexFormat.of().formatHex(archiveDigest.digest()),translations.size(),available);
            try(var audit=new TranslationCoverageAudit(Path.of(args[2]))) {
                for(var path:invalidPaths)audit.file(path,"invalid_json");
                for(var entry:translations.entrySet())if(!originals.containsKey(entry.getKey()))audit.file(entry.getKey(),"missing_source_file");
                for(var entry:originals.entrySet())if(!translations.containsKey(entry.getKey())) {
                    audit.file(entry.getKey(),"unpackaged_source_file");
                    try {records.addAll(auditCollector.collect(entry.getKey(),entry.getValue(),"{}"));}
                    catch(Exception e){audit.file(entry.getKey(),"invalid_json");}
                }
                for(var record:records)audit.record(record,index);
            }
            var store=new TranslationIndexStore(Files.createTempDirectory("limbus-audit-index-"));
            var nativeIndex=store.activate(index);
            Files.writeString(Path.of(args[2],"native-index.path"),nativeIndex.toString());
            System.out.println("nativeIndex="+nativeIndex);
            try(var out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(Path.of(args[2],"runtime-probes.bin"))))) {
                out.writeInt(records.size());
                for(var record:records) {
                    text(out,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(record.getSource().getBytes(StandardCharsets.UTF_8)),0,8));
                    text(out,record.getSource());text(out,record.getTranslation());
                }
            }
            System.out.printf("sourceFiles=%d patchFiles=%d pairedFiles=%d invalidFiles=%d records=%d exactEntries=%d terms=%d conflicts=%d%n",
                originals.size(),translations.size(),available,invalid,index.getSourceRecordCount(),index.getUniqueEntries().size(),index.getTermEntries().size(),index.getConflictCount());
        }
    }
}
