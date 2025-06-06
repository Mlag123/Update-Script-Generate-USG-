import java.io.File;
import java.io.IOException;
import java.util.Objects;

public class AromaGenerater extends AromaGenerator {
    public static void Gener() {
        new File(aromafolder).mkdirs();
        new File(aromathemefolderto).mkdirs();
        if (!new File(aromafolder + System.getProperty("file.separator") + "langs").exists()) {
            new File(aromafolder + System.getProperty("file.separator") + "langs").mkdirs();//создаем папку со всеми подпапками
        }
//копитруем файлы и темы -->
        new File(modDir).mkdirs();//папка с модами
        new File(modDir + System.getProperty("file.separator") + instance1.foldermods.getText()).mkdirs();
        Functions.copyDir(pathBin + System.getProperty("file.separator") + "aroma" + System.getProperty("file.separator") + "icons", dirScript + System.getProperty("file.separator") + "aroma" + System.getProperty("file.separator") + "icons");
        Functions.copyDir(pathBin + System.getProperty("file.separator") + "aroma" + System.getProperty("file.separator") + "ttf", dirScript + System.getProperty("file.separator") + "aroma" + System.getProperty("file.separator") + "ttf");
        Functions.copyFile(aromabin, aromabinto);
        if (instance1.themeCheckBox.isSelected()) {
            new File(aromathemefolderto).mkdirs();
            Functions.copyDir(aromathemefolder + System.getProperty("file.separator") + instance1.themeslist.getSelectedItem().toString(), aromathemefolderto + System.getProperty("file.separator") + instance1.themeslist.getSelectedItem().toString());
        }
        Functions.copyFile(updatebinary71, dirScript + System.getProperty("file.separator") + "update-binary-installer");
//< -- ...
//выводим config -->
        Functions.writefile(Generator.bootsh, "#!/sbin/sh");
        Functions.writefile(Generator.bootsh, "> /tmp/configs");
        Functions.writefile(Generator.bootsh, "busybox find /dev/block -type l | busybox sed '/mmcblk/d' | busybox xargs -n 1 busybox ls -l | busybox sed '/mmcblk/!d;s/.*[ \\t][ \\t]*\\([^ \\t][^ \\t]*\\)[ \\t][ \\t]*->[ \\t][ \\t]*\\([^ \\t][^ \\t]*\\)$/\\1=\\2/' | busybox sed 's/^\\(.\\)/\\/\\1/;s/=/=\\//;s/.*\\/\\([^=\\/][^=\\/]*\\)=\\/\\(.*\\)/\\1=\\2/;/^p[0-9]/d' | sort -u | sed '/^$/d'  >> /tmp/configs");
        Functions.writefile(Generator.bootsh, "if [ -z \"$(cat /tmp/configs)\" ]; then");
        Functions.writefile(Generator.bootsh, "	busybox awk '{ print $1\" \"\"=\"\" \"$3 }' $(busybox grep -r \"\\/boot\" /etc | busybox sed 's/:.*//') | busybox sed 's/^\\///; /#/d' >> /tmp/configs");
        Functions.writefile(Generator.bootsh, "fi");
//выводим config <--			
//скрипт -->
        Functions.writefile(Generator.UpdaterScript, "# generated by " + Generator.NAMEPROGRAMM + " created be blackeangel 4pda.ru");
        Functions.writefile(Generator.UpdaterScript, "ifelse(is_mounted(\"/system\"), unmount(\"/system\"));");
        Functions.writefile(Generator.UpdaterScript, "run_program(\"/sbin/busybox\", \"mount\", \"/system\");");
        Functions.writefile(Generator.UpdaterScript, "package_extract_file(\"META-INF/com/google/android/config\", \"/tmp/config\");");
        Functions.writefile(Generator.UpdaterScript, "set_metadata(\"/tmp/config\", \"uid\", 0, \"gid\", 0, \"mode\", 0777);");
        Functions.writefile(Generator.UpdaterScript, "run_program(\"/sbin/busybox\", \"chmod\", \"777\", \"/tmp/config\");");
        Functions.writefile(Generator.UpdaterScript, "run_program(\"/tmp/config\");");
        // Functions.writefile(Generator.UpdaterScript, "delete(\"/tmp/config\");");
//< -- скрипт
        aromageneratorchangelog.AromaGeneratorChangeLog();
        aromageneratorconfig.AromaGeneratorConfig();
        //aromageneratorlangeng.AromaGeneratorEngLang();
        //aromageneratorlangrus.AromaGeneratorRusLang();

//создаем архив -->
        if (new File(Generator.FlashZip).exists()) {
            Functions.deletefile(new File(Generator.FlashZip));//удаляем архив если есть
        }
        Functions.Zipping();
        Functions.deletefile(new File(Generator.dirTmp));//удаляем папку tmp
//создаем архив <--
        if (Objects.equals(Generator.LANGUAGE, "русский")) {
            Generator.instance.toolbarLabel.setText("Готов!");
        } else {
            Generator.instance.toolbarLabel.setText("Ready!");
        }
    }
}