Option Explicit

Dim shell
Dim fileSystem
Dim scriptDirectory
Dim command
Dim index
Dim argument
Dim waitForExit
Dim background
Dim exitCode

Set shell = CreateObject("WScript.Shell")
Set fileSystem = CreateObject("Scripting.FileSystemObject")
scriptDirectory = fileSystem.GetParentFolderName(WScript.ScriptFullName)
waitForExit = False
background = False
command = "powershell.exe -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File """ _
    & scriptDirectory & "\run-nytweetdeck.ps1"""

For index = 0 To WScript.Arguments.Count - 1
    argument = WScript.Arguments(index)
    If LCase(argument) = "--wait" Then
        waitForExit = True
    ElseIf LCase(argument) = "--background" Then
        background = True
    Else
        command = command & " """ & Replace(argument, """", """""") & """"
    End If
Next

If background Then
    command = command & " -Background"
End If

exitCode = shell.Run(command, 0, waitForExit)
WScript.Quit exitCode
