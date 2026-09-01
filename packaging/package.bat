@echo off

REM Read the settings file
FOR /F "tokens=1,2 delims==" %%G IN (settings\general) DO (set %%G=%%H)

mkdir work\
mkdir out\

echo Downloading and extracting Temurin...
set jdk_zip=OpenJDK%JDK_MAJOR%U-jdk_@ARCH_TEMURIN@_@OS@_hotspot_%JDK_MAJOR%%JDK_MINOR%_%JDK_PATCH%.zip
set jdk_bin=work\jdk-%JDK_MAJOR%%JDK_MINOR%+%JDK_PATCH%\bin
powershell (new-object System.Net.WebClient).DownloadFile('https://github.com/adoptium/temurin%JDK_MAJOR%-binaries/releases/download/jdk-%JDK_MAJOR%%JDK_MINOR%+%JDK_PATCH%/%jdk_zip%', 'work\%jdk_zip%')
powershell Expand-Archive work\%jdk_zip% -DestinationPath work\
del work\%jdk_zip%

echo Downloading and extracting Wix Toolset...
set wix_msi=wix-cli-x64.msi
set wix_exe="work\wix\PFiles64\WiX Toolset v7.0\bin\wix.exe" -acceptEula wix7
powershell (new-object System.Net.WebClient).DownloadFile('https://github.com/wixtoolset/wix/releases/download/v7.0.0/%wix_msi%', 'work\%wix_msi%')
msiexec /a work\%wix_msi% TARGETDIR=%~dp0work\wix\ /qn
del work\%wix_msi%

echo Collecting minimized JRE...
%jdk_bin%\jlink @settings\jlink --output work\runtime\

echo Collecting installation image...
%jdk_bin%\jpackage @settings\jpackage @settings\jpackage-windows --name cinecred --icon images\icon.ico --input app\ --runtime-image work\runtime\ --dest work\image\
copy resources\universal\LEGAL work\image\cinecred\
powershell "$s = Get-Content -Path work\image\cinecred\app\cinecred.cfg | ForEach-Object { $_; if ($_ -eq '[Application]') { 'win.norestart=true' } }; $s | Out-File -FilePath work\image\cinecred\app\cinecred.cfg -Encoding Default"

echo Assembling ZIP archive...
powershell Compress-Archive work\image\Cinecred -DestinationPath out\cinecred-@VERSION@-@OS@-@ARCH@.zip

echo Assembling MSI package...
REM Collect all languages for which we have .wxl files in three variables (en-US always comes first):
REM lang_tags = en-US de-DE ...
REM lang_codes = 1033 1031 ...
REM lang_tags_and_codes = en-US/1033 de-DE/1031 ...
set lang_tags=
set lang_codes=
set lang_tags_and_codes=
FOR %%F IN (resources\msi\l10n\*) DO (
    FOR /F "tokens=1 USEBACKQ" %%C IN (`powershell "[System.Globalization.CultureInfo]::GetCultureInfo('%%~nF').LCID"`) DO (
        call :append_lang %%~nF %%C
    )
)
REM Cache the Wix UI extension
%wix_exe% extension add WixToolset.UI.wixext
FOR %%T IN (%lang_tags%) DO (
    REM Assemble a separate .msi for every language
    %wix_exe% build -arch @ARCH_WIX@ -bindpath image=%~dp0work\image\cinecred\ -bindvariable WixUIBannerBmp=images\banner.bmp -bindvariable WixUIDialogBmp=images\sidebar.bmp -d Icon=images\icon.ico -culture %%T -ext WixToolset.UI.wixext -cabcache work\wixcab\ -pdbtype none -o work\wixmsi\%%T.msi resources\msi\*.wxs resources\msi\l10n\*.wxl
    REM Obtain .mst transformations from the en-US .msi to every other language's .msi
    IF not "%%T" == "en-US" (
        %wix_exe% msi transform -t language -o work\wixmst\%%T.mst work\wixmsi\en-US.msi work\wixmsi\%%T.msi
        REM Delete the localized .msi files immediately because they can be quite big, and there's a lot of them
        del work\wixmsi\%%T.msi
    )
)
REM Add the transformations as substorages to the en-US .msi
copy work\wixmsi\en-US.msi work\out.msi
FOR %%P IN (%lang_tags_and_codes%) DO (FOR /F "tokens=1,2 delims=/" %%T IN ("%%P") DO (
    IF not "%%T" == "en-US" (resources\msi\scripts\AddSubstorage.vbs work\out.msi work\wixmst\%%T.mst %%U)
))
REM Write all available language codes into the .msi
resources\msi\scripts\SetPackageLanguage.vbs work\out.msi %lang_codes%
move work\out.msi out\cinecred-@VERSION@-@ARCH@.msi

echo Cleaning up...
rmdir /S /Q work\

goto :eof


:append_lang
IF not defined lang_tags (
    set lang_tags=%1& set lang_codes=%2& set lang_tags_and_codes=%1/%2
) ELSE (IF "%1" == "en-US" (
    set lang_tags=%1,%lang_tags%& set lang_codes=%2,%lang_codes%& set lang_tags_and_codes=%1/%2,%lang_tags_and_codes%
) ELSE (
    set lang_tags=%lang_tags%,%1& set lang_codes=%lang_codes%,%2& set lang_tags_and_codes=%lang_tags_and_codes%,%1/%2
))
