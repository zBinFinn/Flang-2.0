This is a project that compiles text in the form of .fl files to diamondfire code templates for minecraft.
The target is to output ./info/example_template.txt item nbt, the main part of that is gzip base64 compressed 
./info/decoded_templates/* json which describes the code.
Read about the language syntax in ./info/specification.typ and a collection of all actions, 
sounds, gamevalues, etc. in ./info/action_dump.json.