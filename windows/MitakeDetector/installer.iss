#ifndef AppVersion
  #define AppVersion "0.3.0"
#endif
[Setup]
AppId={{26C26027-148C-4F4F-B192-30E6AB470B2E}
AppName=漲停板探測器-雷允澤製
AppVersion={#AppVersion}
AppPublisher=雷允澤
AppPublisherURL=https://github.com/rex0966445518/stock-monitor-updates
AppSupportURL=https://github.com/rex0966445518/stock-monitor-updates/issues
AppUpdatesURL=https://github.com/rex0966445518/stock-monitor-updates/releases/tag/mitake-latest
DefaultDirName={localappdata}\Programs\MitakeDetector
DefaultGroupName=漲停板探測器-雷允澤製
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
OutputDir=release-output
OutputBaseFilename=MitakeDetector-Setup-v{#AppVersion}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\MitakeDetector.exe
CloseApplications=yes
RestartApplications=no
AppMutex=Local\RexMitakeDetector
SetupLogging=yes
VersionInfoVersion={#AppVersion}.0
[Languages]
Name: "chinesetraditional"; MessagesFile: "ChineseTraditional.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"
[Tasks]
Name: "desktopicon"; Description: "建立桌面捷徑"; GroupDescription: "捷徑："
[Files]
Source: "dist\MitakeDetector\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
[InstallDelete]
Type: files; Name: "{autodesktop}\三竹盤中轉強偵測器.lnk"
Type: files; Name: "{userprograms}\三竹盤中轉強偵測器\三竹盤中轉強偵測器.lnk"
[Icons]
Name: "{group}\漲停板探測器-雷允澤製"; Filename: "{app}\MitakeDetector.exe"
Name: "{autodesktop}\漲停板探測器-雷允澤製"; Filename: "{app}\MitakeDetector.exe"; Tasks: desktopicon
[Run]
Filename: "{app}\MitakeDetector.exe"; Description: "啟動漲停板探測器-雷允澤製"; Flags: nowait; Check: ShouldLaunch
[Code]
function ShouldLaunch: Boolean;
begin
  Result := (not WizardSilent) or (ExpandConstant('{param:LAUNCHAPP|0}') = '1');
end;
